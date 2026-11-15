package app.plamotranslate.pipeline.speech

/**
 * 判定：1枚のカードの話し手の時系列。カードの話し手（言語と、確かか仮か）と、話し手が代わった位置の手がかりを、
 * 位置の付いた根拠（話し中の窓・文の全体の話し手）から決める。カードの話し手と区切る位置の判断はここだけで行い、
 * [SpeechSegmenter] は返した判定（[Verdict]）を実行する（区切る・継ぎ目を探す・書き起こす）。判定し直しはしない。
 *
 * - 話し中の窓：カードの言語が無ければ、その言語を仮に決める。カードの言語と同じ窓が [switchChecks] 回続けば確かにする。
 *   違う言語の窓が [switchChecks] 回続けば、確かなカードでは「最初に違った窓」から継ぎ目を探させ（[Verdict.Seek]）、
 *   仮のカードでは、話し始めの窓の誤りか交代かをカードの全体で確かめさせる（[Verdict.Recheck]）。
 *   交代に気づくのは違う窓が続いた後だが、継ぎ目は最初に違った窓から探すので、新しい話し手の頭が前のカードに残らない
 *   （2026-11-15 の beta.3 の試用で、今から3.5秒前より後ろだけを探し、新しい話し手の頭の1〜2秒が前のカードに入っていた）。
 * - 文の全体：確かなカードの話し手と違えば、その文の頭で区切らせる（[Verdict.Cut]）。ただしカードの頭から始まる文は、区切る前が
 *   無いので、カードの話し手を替える。確かでなければ、文の話し手をカードの話し手にする。
 * - 閉じた後（[close]）：区切らせない。確かな話し手は変えず、届いた文もカードの話し手の言語で書き起こさせる（同じカードの中で
 *   言語を決め直さない）。まだ確かでなければ、届いた文で決める。
 * 決められなかった根拠（null）は、交代の根拠にも反証にもしない。
 *
 * 位置は聞き始めからのサンプル数。[start] はカードの始まり。
 */
class SpeakerTrack(private val start: Long, private val switchChecks: Int) {
    /** カードの話し手の言語（[SpeakerJudge] の組）。決まるまで null。 */
    var lang: String? = null
        private set

    /** 話し手が確かか（文の全体で決まったか、窓が続けて同じ言語）。 */
    var sure = false
        private set

    private var agreeing = 0
    private var mismatches = 0
    private var otherLang: String? = null

    /** カードの言語と違う窓が続き始めた、最初の窓。 */
    private var firstMismatch: LongRange? = null

    /** カードを区切ると決めたか。 */
    private var closed = false

    sealed interface Verdict {
        /** 何もしない。 */
        data object Keep : Verdict

        /** カードの言語が [lang] に変わった（途中結果を書き起こし直す）。 */
        data class Assign(val lang: String) : Verdict

        /** カードの全体の点数で、話し始めの窓の誤りか、[lang] への交代かを確かめる（結果は [recheck] に渡す）。 */
        data class Recheck(val lang: String) : Verdict

        /** [lang] の話し手に代わった。継ぎ目を [from] から今までの間で探し、見つかれば区切る（見つからなければ [seamMissed]）。 */
        data class Seek(val lang: String, val from: Long) : Verdict

        /** [lang] の話し手に代わった。[at] で区切る（後ろは [lang] の確かなカード）。 */
        data class Cut(val lang: String, val at: Long) : Verdict
    }

    /** カードの話し手を [lang] にする（前のカードから引き継ぐ・決められないときの仮）。変わったら true。 */
    fun assign(lang: String, sure: Boolean): Boolean {
        val changed = this.lang != lang
        this.lang = lang
        this.sure = sure
        if (changed) {
            agreeing = 0
            clearMismatch()
        }
        return changed
    }

    /** 話し中の窓 [from]〜[to] の話し手が [judged] と決まった（決められなければ null）。 */
    fun window(from: Long, to: Long, judged: String?): Verdict {
        judged ?: return Verdict.Keep
        val current = lang
        return when {
            current == null -> {
                assign(judged, sure = false)
                agreeing = 1
                Verdict.Assign(judged)
            }
            judged == current -> {
                clearMismatch()
                if (++agreeing >= switchChecks) sure = true
                Verdict.Keep
            }
            else -> {
                if (otherLang != judged) clearMismatch()
                otherLang = judged
                val first = firstMismatch ?: (from..to).also { firstMismatch = it }
                when {
                    ++mismatches < switchChecks -> Verdict.Keep
                    sure -> seek(judged, first)
                    else -> Verdict.Recheck(judged)
                }
            }
        }
    }

    /** カードの全体の話し手が [judged] と決まった（[Verdict.Recheck] の答え）。全体も [lang] なら、話し始めの窓の誤りだった。 */
    fun recheck(lang: String, judged: String?): Verdict {
        val first = firstMismatch ?: return Verdict.Keep
        if (judged != lang) return seek(lang, first)
        val streak = mismatches
        assign(lang, sure = true)
        agreeing = streak
        return Verdict.Assign(lang)
    }

    /** カードを区切ると決めた。この後の文の判定では区切らせない。 */
    fun close() {
        closed = true
    }

    /** 継ぎ目が見つからなかった（短い窓の聞き違い）。違った窓の数え直しから始める。 */
    fun seamMissed() = clearMismatch()

    /** [from] から始まる文の全体の話し手が [judged] と決まった（決められなければ null）。 */
    fun phrase(from: Long, judged: String?): Verdict {
        judged ?: return Verdict.Keep
        val current = lang
        if (current != null && sure && judged != current) {
            if (closed) return Verdict.Keep
            if (from > start) return Verdict.Cut(judged, from)
        }
        if (judged != current) {
            assign(judged, sure = true)
            return Verdict.Assign(judged)
        }
        sure = true
        return Verdict.Keep
    }

    /**
     * 最初に違った窓 [first] のどこかで話し手が代わった（窓の多くが新しい言語なので、交代は窓の中ほどより前）。
     * 窓の頭の少し前から探させる。
     */
    private fun seek(judged: String, first: LongRange): Verdict =
        Verdict.Seek(judged, (first.first - SEEK_BEFORE_SAMPLES).coerceAtLeast(start))

    private fun clearMismatch() {
        mismatches = 0
        otherLang = null
        firstMismatch = null
    }

    private companion object {
        /** 最初に違った窓の頭より前に、継ぎ目を探す長さ（窓の頭の語が前の話し手の言語に聞こえて、窓が遅れて違い始めることがある）。 */
        const val SEEK_BEFORE_SAMPLES = SpeechEngine.SAMPLE_RATE.toLong()
    }
}
