package app.plamotranslate.pipeline.speech

/**
 * 判定：1枚のカードの話し手の時系列。カードの話し手（言語と、確かか仮か）と、話し手が代わった位置の手がかりを、
 * 位置の付いた根拠から決める。カードの話し手と区切る位置の判断はここだけで行い、[SpeechSegmenter] は返した判定（[Verdict]）を
 * 実行する（区切る・境界を探す・書き起こす）。判定し直しはしない。
 *
 * 根拠は1本の時系列にする：話し中の区間（短い音声の言語）を、続けて同じ言語だった区間ごとにまとめた「連続区間」にする。
 * 連続区間が2回以上の判定からなり、区間が覆った音声が [minTurn] 以上なら、その言語の話し手が話したとみなす（連続区間が確か）。
 * 言語の識別は短い音声では外れるので、それより短い交代は取りにいかない（docs/設計/会話翻訳.md「取りにいくことと諦めること」）。
 * 1回の判定は、音声が長くても外れることがある（PC で、日本語の頭の1.5秒の区間を韓国語 0.95〜0.99 と判定した）ので、それだけでは決めない。
 * 回数ではなく音声の長さで数えるのは、区間の間隔が端末の速さで変わるため。
 *
 * - カードの言語と違う連続区間が確かになった：カードの話し手が確かなら境界を探させる（[Verdict.Seek]）。境界は、
 *   カードの話し手だと分かっている最後の根拠（カードの言語と同じだった最後の区間・文の頭）から、連続区間の最初の区間の終わりまでで探す。
 *   交代はこの間にあり、気づくまでの遅れに依らない。交代の根拠は連続区間なので、境界探しは位置を探すだけで、後ろの音声の
 *   全体で確かめ直さない（2人の話が行き来する区間では、全体の多数決が新しい言語にならず、区切れなかった）。まだ確かでなければ、仮の言語（話し始めの短い区間・前の話し手の引き継ぎ）が
 *   誤りだったとみなし、カードの言語を替える。
 * - 文の全体（文が終わったときのスコア）：多数決なので、2人をまたぐ文では少ない方の話し手が消える（2026-11-15、作者の会話の録音で、
 *   英語 0.86〜0.98 の区間が続いた範囲を、またいだ文の全体が日本語 0.97〜1.00 として、英語の文を日本語で書き起こした）。
 *   文の中に [minTurn] に届いた連続区間があれば、話し手は区間が決めているので、文の全体は使わない。無ければ（短い文・区間の無い文）、
 *   確かなカードの話し手と違えば文の頭で区切らせ（[Verdict.Cut]。カードの頭から始まる文なら言語を替える）、
 *   確かでなければ文の話し手をカードの話し手にする。
 * - 閉じた後（[close]）：区切らせない。確かな話し手は変えず、届いた文もカードの話し手の言語で書き起こさせる。
 *   まだ確かでなければ、届いた文で決める。
 * 決められなかった区間（null）は、連続区間を伸ばしも切りもしない（覆った長さにも数えないので、離れた2回の判定は確かにならない）。
 * 境界が見つからなかった連続区間（[seamMissed]）は聞き違いとして、確かな根拠から外す。
 * - 書き起こしの言語（[transcribed]）：言語を自分で判定する認識器（SenseVoice）が、カードの文・区切った残り・途中結果を
 *   書き起こしたときに判定した言語。区間（1.5秒）より長い音声の判定なので、カードの表示の言語（[label]）では区間より先にする
 *   （2026-11-19、韓国語の話し手の1.5秒の区間を SenseVoice が約4割日本語と判定し、同じカードの全体は8枚とも韓国語と判定した）。
 *   区切る位置と書き起こす言語の指定には使わない：区切った後のカードは1人の話し（の大部分）なので、カードの範囲の多数決を
 *   表示の言語に使えるが、区切る前の範囲の多数決は、2人をまたぐと少ない方の話し手を消す（設計の原則の7）。
 *
 * 根拠の優先度は用途ごとに決まる：区切る位置と [lang]（書き起こす言語の指定）は 区間の連続区間 ＞ 文の全体、
 * 表示の言語（[label]）は 書き起こしの言語 ＞ [lang]。確定したカードの言語の利用者の指定は、会話の中核
 * （`ConversationSession`）が受ける（カードを閉じた後なので、この時系列は無い）。
 *
 * 位置は聞き始めからのサンプル数。[start] はカードの始まり、[minTurn] はサンプル数。
 */
class SpeakerTrack(private val start: Long, private val minTurn: Long) {
    /** カードの話し手の言語（[SpeakerJudge] の組）。決まるまで null。 */
    var lang: String? = null
        private set

    /** 話し手が確かか（連続区間が [minTurn] に届いた、文の全体で決まった、確かな話し手を引き継いだ）。 */
    var sure = false
        private set

    /** 続けて同じ言語だった区間の並び（連続区間）。[from]〜[to] は区間の範囲を合わせたもの、[lastFrom] は最後の区間の頭。 */
    private inner class Run(val lang: String, val from: Long, var to: Long, var lastFrom: Long) {
        /** 最初の区間の終わり。交代はこれより前にある。 */
        val firstTo = to

        var judgments = 1

        /** 区間が覆った音声の長さ（重なりは1回だけ数える）。 */
        var covered = to - from

        /** 境界が見つからず、聞き違いとみなした。 */
        var refuted = false

        /** 確かな連続区間か（2回以上の判定が [minTurn] 以上の音声を覆う）。 */
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

    /** 境界を探させている言語。結果が届くまで、同じ交代で探させ直さない。 */
    private var seeking: String? = null

    /** カードの話し手だと分かっている最後の根拠（カードの言語と同じだった区間・文）の始まり。無ければカードの始まり。 */
    private var lastAgreeFrom = start

    /** カードを区切ると決めたか。 */
    private var closed = false

    sealed interface Verdict {
        /** 何もしない。 */
        data object Keep : Verdict

        /** カードの言語が [lang] に変わった（途中結果を書き起こし直す）。 */
        data class Assign(val lang: String) : Verdict

        /** [lang] の話し手に代わった。境界を [from]〜[to] で探し、見つかれば区切る（見つからなければ [seamMissed]）。 */
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

    /** [from]〜[to] の話し中の区間の話し手が [judged] と決まった（決められなければ null）。 */
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

    /** 境界が見つからなかった（区間の聞き違い）。その連続区間は使い終え、次の区間から数え直す。 */
    fun seamMissed() {
        seeking = null
        runs.lastOrNull()?.refuted = true
    }

    /** [from]〜[to] の文の全体の話し手が [judged] と決まった（決められなければ null）。 */
    fun phrase(from: Long, to: Long, judged: String?): Verdict {
        judged ?: return Verdict.Keep
        // 文の中で区間が話し手を決めていれば、文の全体の多数決で上書きしない
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

    /** 書き起こしの言語の根拠。頭の位置ごとに1つ（同じ頭から書き起こし直したら、新しい方に置き換える）。 */
    private class Transcript(val to: Long, val lang: String)

    private val transcripts = HashMap<Long, Transcript>()

    /**
     * [from]〜[to] を書き起こし、認識器が言語を [judged] と判定した（話し手の根拠にならなければ null）。同じ頭の前の根拠を置き換える：
     * 途中結果は同じ頭から伸びていき、文や区切った残りは、同じ頭の途中結果を書き起こし直したものになる。
     */
    fun transcribed(from: Long, to: Long, judged: String?) {
        if (judged == null) transcripts.remove(from) else transcripts[from] = Transcript(to, judged)
    }

    /**
     * カードの表示の言語（[end] までの範囲）。書き起こしの言語の根拠を音声の長さで数え、一番長い言語が [minTurn] 以上で、
     * 根拠の半分を超えればその言語。届かなければ [lang]（区間と文の全体で決めた話し手）。
     */
    fun label(end: Long): String? {
        val totals = HashMap<String, Long>()
        for ((from, t) in transcripts) {
            if (from >= end) continue
            val length = minOf(t.to, end) - from
            if (length > 0) totals[t.lang] = (totals[t.lang] ?: 0) + length
        }
        val best = totals.maxByOrNull { it.value } ?: return lang
        return if (best.value >= minTurn && best.value * 2 > totals.values.sum()) best.key else lang
    }

    /** 今までの根拠でのカードの表示の言語（話し中のカード）。 */
    fun label(): String? = label(Long.MAX_VALUE)

    /** 区間を連続区間に足す。最後の連続区間と同じ言語なら伸ばし、違えば新しい連続区間を始める。 */
    private fun extend(judged: String, from: Long, to: Long): Run {
        val last = runs.lastOrNull()
        if (last != null && last.lang == judged && !last.refuted) {
            last.add(from, to)
            return last
        }
        return Run(judged, from, to, from).also { runs += it }
    }
}
