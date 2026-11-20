package app.plamotranslate.pipeline.speech

/**
 * 1枚のカードの書き起こし：文ごとの書き起こしと、まだ終わっていない文の途中結果をつないで、途中結果と確定の文字にする
 * （docs/設計/会話翻訳.md「カードの区切り方」）。いつ文にするか・いつ区切るか・どの言語で書き起こすかは決めない（[SpeechSegmenter]）。
 * 位置は聞き始めからのサンプル数。[start] はカードの始まり。
 */
internal class CardTranscript(private val start: Long) {
    /**
     * 文。[from]〜[end] は聞き始めからの位置。[lang] は話し手の言語（決めるまで null）、[recognition] は書き起こし（届くまで null）。
     * [audio] は文を作った時点の音声の写し。話し手を決めてから書き起こすまでに、ためた音声が区切られても書き起こせるようにする。
     */
    class Phrase(val from: Long, val end: Long, var lang: String? = null, val audio: FloatArray? = null) {
        var recognition: Recognition? = null
    }

    private val list = mutableListOf<Phrase>()

    /** カードの文（話した順）。 */
    val phrases: List<Phrase> get() = list

    /** まだ終わっていない文の途中結果と、それを書き起こした音声の始まり。 */
    private var tail: Recognition? = null
    private var tailFrom = 0L

    /** 直前に出した途中結果の文字。 */
    var shown = ""
        private set

    /** 区切ると決めたときの部品（文と区切った残り）。区切るまで null。 */
    private var parts: List<Phrase>? = null

    fun add(phrase: Phrase) {
        list += phrase
    }

    operator fun contains(phrase: Phrase): Boolean = phrase in list

    /** 書き起こした（頼んだ）文の終わり。ここから後ろが、まだ書き起こしていない音声。文が無ければカードの始まり。 */
    val stitchedEnd: Long get() = list.lastOrNull()?.end ?: start

    /** [from] から書き起こした途中結果 [r] を、まだ終わっていない文の途中結果にする。 */
    fun partial(from: Long, r: Recognition) {
        tail = r
        tailFrom = from
    }

    /** 途中結果を捨てる（言語が変わって古くなった）。 */
    fun dropPartial() {
        tail = null
    }

    /**
     * 今出す途中の文字：書き起こした文（届いた分だけ、頭から続くもの）と、まだ終わっていない文の途中結果をつなぐ。
     * 途中結果は、最後の文の後ろを書き起こしたものだけをつなぐ（文が増えたら古い）。何も無ければ null。
     */
    fun current(): Recognition? {
        val done = mutableListOf<Recognition>()
        var complete = true
        for (p in list) {
            val r = p.recognition
            if (r == null) {
                complete = false
                break
            }
            done += r
        }
        val tail = tail
        if (complete && tail != null && tailFrom == stitchedEnd) done += tail
        return if (done.isEmpty()) null else TranscriptJoin.stitch(done)
    }

    /** [text] を途中結果として出すか（空でなく、直前に出したものと違う）。出すなら出したものとして覚える。 */
    fun show(text: String): Boolean {
        if (text.isEmpty() || text == shown) return false
        shown = text
        return true
    }

    /** [end] より後ろで終わる文を外す（区切る位置より後ろの文は、次のカードの文になる）。 */
    fun cut(end: Long) {
        list.removeAll { it.end > end }
    }

    /** 区切ると決めた。確定は、今の文と [rest]（区切る位置までのまだ書き起こしていない音声。無ければ null）をつなぐ。 */
    fun close(rest: Phrase?) {
        parts = list + listOfNotNull(rest)
    }

    /** 区切ると決め、部品の書き起こしがすべて届いていれば、つないだ確定の文字。まだなら null。 */
    fun final(): Recognition? {
        val parts = parts ?: return null
        if (parts.any { it.recognition == null }) return null
        return TranscriptJoin.stitch(parts.mapNotNull { it.recognition })
    }
}
