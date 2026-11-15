package app.plamotranslate.pipeline.speech

/**
 * 判定：1枚のカードの話し手の時系列。カードの話し手（言語と、確かか仮か）と、話し手が代わった位置の手がかりを、
 * 位置の付いた根拠から決める。カードの話し手と区切る位置の判断はここだけで行い、[SpeechSegmenter] は返した判定（[Verdict]）を
 * 実行する（区切る・継ぎ目を探す・書き起こす）。判定し直しはしない。
 *
 * 根拠は1本の時系列にする：話し中の窓（短い音声の言語）を、続けて同じ言語だった窓ごとにまとめた「連なり」にする。
 * 連なりが2回以上の判定からなり、窓が覆った音声が [minTurn] 以上なら、その言語の話し手が話したとみなす（連なりが確か）。
 * 言語の識別は短い音声では外れるので、それより短い交代は取りにいかない（docs/設計/会話翻訳.md「取りにいくことと諦めること」）。
 * 1回の判定は、音声が長くても外れることがある（PC で、話し始めの2秒の日本語を韓国語 0.95 と判定した）ので、それだけでは決めない。
 * 回数ではなく音声の長さで数えるのは、窓の間隔が端末の速さで変わるため。
 *
 * - カードの言語と違う連なりが確かになった：カードの話し手が確かなら継ぎ目を探させる（[Verdict.Seek]）。継ぎ目は、
 *   カードの話し手だと分かっている最後の根拠（カードの言語と同じだった最後の窓・文の頭）から、連なりの最初の窓の終わりまでで探す。
 *   交代はこの間にあり、気づくまでの遅れに依らない。交代の根拠は連なりなので、継ぎ目探しは位置を探すだけで、後ろの音声の
 *   全体で確かめ直さない（2人の話が行き来する区間では、全体の多数決が新しい言語にならず、区切れなかった）。まだ確かでなければ、仮の言語（話し始めの短い窓・前の話し手の引き継ぎ）が
 *   誤りだったとみなし、カードの言語を替える。
 * - 文の全体（文が終わったときの点数）：多数決なので、2人をまたぐ文では少ない方の話し手が消える（2026-11-15、作者の会話の録音で、
 *   英語 0.86〜0.98 の窓が続いた区間を、またいだ文の全体が日本語 0.97〜1.00 として、英語の文を日本語で書き起こした）。
 *   文の中に [minTurn] に届いた連なりがあれば、話し手は窓が決めているので、文の全体は使わない。無ければ（短い文・窓の無い文）、
 *   確かなカードの話し手と違えば文の頭で区切らせ（[Verdict.Cut]。カードの頭から始まる文なら言語を替える）、
 *   確かでなければ文の話し手をカードの話し手にする。
 * - 閉じた後（[close]）：区切らせない。確かな話し手は変えず、届いた文もカードの話し手の言語で書き起こさせる。
 *   まだ確かでなければ、届いた文で決める。
 * 決められなかった窓（null）は、連なりを伸ばしも切りもしない（覆った長さにも数えないので、離れた2回の判定は確かにならない）。
 * 継ぎ目が見つからなかった連なり（[seamMissed]）は聞き違いとして、確かな根拠から外す。
 *
 * 位置は聞き始めからのサンプル数。[start] はカードの始まり、[minTurn] はサンプル数。
 */
class SpeakerTrack(private val start: Long, private val minTurn: Long) {
    /** カードの話し手の言語（[SpeakerJudge] の組）。決まるまで null。 */
    var lang: String? = null
        private set

    /** 話し手が確かか（連なりが [minTurn] に届いた、文の全体で決まった、確かな話し手を引き継いだ）。 */
    var sure = false
        private set

    /** 続けて同じ言語だった窓のまとまり。[from]〜[to] は窓の範囲を合わせたもの、[lastFrom] は最後の窓の頭。 */
    private inner class Run(val lang: String, val from: Long, var to: Long, var lastFrom: Long) {
        /** 最初の窓の終わり。交代はこれより前にある。 */
        val firstTo = to

        var judgments = 1

        /** 窓が覆った音声の長さ（重なりは1回だけ数える）。 */
        var covered = to - from

        /** 継ぎ目が見つからず、聞き違いとみなした。 */
        var refuted = false

        /** 確かな連なりか（2回以上の判定が [minTurn] 以上の音声を覆う）。 */
        val established get() = !refuted && judgments >= 2 && covered >= minTurn

        /** [from]〜[to] の中で確かか。 */
        fun establishedWithin(from: Long, to: Long) = established && minOf(this.to, to) - maxOf(this.from, from) >= minTurn

        fun add(from: Long, to: Long) {
            covered += (to - maxOf(from, this.to)).coerceAtLeast(0)
            this.to = maxOf(this.to, to)
            lastFrom = from
            judgments++
        }
    }

    private val runs = mutableListOf<Run>()

    /** 継ぎ目を探させている言語。結果が届くまで、同じ交代で探させ直さない。 */
    private var seeking: String? = null

    /** カードの話し手だと分かっている最後の根拠（カードの言語と同じだった窓・文）の始まり。無ければカードの始まり。 */
    private var lastAgreeFrom = start

    /** カードを区切ると決めたか。 */
    private var closed = false

    sealed interface Verdict {
        /** 何もしない。 */
        data object Keep : Verdict

        /** カードの言語が [lang] に変わった（途中結果を書き起こし直す）。 */
        data class Assign(val lang: String) : Verdict

        /** [lang] の話し手に代わった。継ぎ目を [from]〜[to] で探し、見つかれば区切る（見つからなければ [seamMissed]）。 */
        data class Seek(val lang: String, val from: Long, val to: Long) : Verdict

        /** [lang] の話し手に代わった。[at] で区切る（後ろは [lang] の確かなカード）。 */
        data class Cut(val lang: String, val at: Long) : Verdict
    }

    /** カードの話し手を [lang] にする（前のカードから引き継ぐ・決められないときの仮）。変わったら true。 */
    fun assign(lang: String, sure: Boolean): Boolean {
        val changed = this.lang != lang
        this.lang = lang
        this.sure = sure
        if (changed) seeking = null
        return changed
    }

    /** [from]〜[to] の話し中の窓の話し手が [judged] と決まった（決められなければ null）。 */
    fun window(from: Long, to: Long, judged: String?): Verdict {
        judged ?: return Verdict.Keep
        val run = extend(judged, from, to)
        val current = lang
        return when {
            current == null -> {
                assign(judged, sure = false)
                lastAgreeFrom = from
                Verdict.Assign(judged)
            }
            judged == current -> {
                lastAgreeFrom = from
                if (run.established) sure = true
                Verdict.Keep
            }
            !run.established || seeking == judged -> Verdict.Keep
            !sure -> {
                // 仮の言語は短い根拠からのものだった。後ろの話し手の言語をカードの言語にする（短い頭は諦める）
                assign(judged, sure = true)
                lastAgreeFrom = run.lastFrom
                Verdict.Assign(judged)
            }
            else -> {
                seeking = judged
                Verdict.Seek(judged, lastAgreeFrom, run.firstTo)
            }
        }
    }

    /** カードを区切ると決めた。この後の文の判定では区切らせない。 */
    fun close() {
        closed = true
    }

    /** 継ぎ目が見つからなかった（窓の聞き違い）。その連なりは使い終え、次の窓から数え直す。 */
    fun seamMissed() {
        seeking = null
        runs.lastOrNull()?.refuted = true
    }

    /** [from]〜[to] の文の全体の話し手が [judged] と決まった（決められなければ null）。 */
    fun phrase(from: Long, to: Long, judged: String?): Verdict {
        judged ?: return Verdict.Keep
        // 文の中で窓が話し手を決めていれば、文の全体の多数決で上書きしない
        if (runs.any { it.establishedWithin(from, to) }) return Verdict.Keep
        val current = lang
        if (current != null && sure && judged != current) {
            if (closed) return Verdict.Keep
            if (from > start) return Verdict.Cut(judged, from)
        }
        if (judged != current) {
            assign(judged, sure = true)
            lastAgreeFrom = from
            return Verdict.Assign(judged)
        }
        sure = true
        lastAgreeFrom = maxOf(lastAgreeFrom, from)
        return Verdict.Keep
    }

    /** 窓を連なりに足す。最後の連なりと同じ言語なら伸ばし、違えば新しい連なりを始める。 */
    private fun extend(judged: String, from: Long, to: Long): Run {
        val last = runs.lastOrNull()
        if (last != null && last.lang == judged && !last.refuted) {
            last.add(from, to)
            return last
        }
        return Run(judged, from, to, from).also { runs += it }
    }
}
