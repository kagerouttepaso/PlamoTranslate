package app.plamotranslate.pipeline.speech

/**
 * 判定：認識した文字のうち、雑音や息の聞き違い（あいづちだけの結果）を取り除く（docs/設計/会話翻訳.md「途中結果」）。
 * 中身が残らなければ、言語も無し（雑音）にする。認識器ごとの癖（SenseVoice の語の間の空白）は、認識器の実装が直す。
 */
object TranscriptCleanup {
    /** 後処理した認識結果。文字が空なら、言語も null（話していないもの）にする。 */
    fun clean(raw: Recognition): Recognition {
        val text = dropFillers(raw.text.trim(), raw.lang)
        // 中身のない結果（雑音の聞き違い）の言語は使わない。使うと話し手の交代と誤ってカードが切れる
        return Recognition(text, if (text.isEmpty()) null else raw.lang)
    }

    /**
     * 雑音や息を認識器が聞き違えた結果を取り除く。中身が残らなければ空文字を返す
     * （`docs/判断/2026-11-06_雑音の聞き違いを何も話していないものとして扱う.md`）。
     * - 句読点だけ、またはあいづち・つなぎの語（[FILLERS]）だけの結果（「Yeah. Yeah.」も）は、何も話していないとみなす。
     * - 日本語・韓国語・中国語の文の頭と末尾に付いた英語のあいづちは取り除く（文の間の雑音が混ざったもの）。
     * 「Yes」「OK」「No」は相手の本当の返事として多いので消さない。
     */
    fun dropFillers(text: String, lang: String?): String {
        var t = text
        if (lang != "en") {
            t = LEADING_FILLERS.replace(t, "")
            t = TRAILING_FILLERS.replace(t, "")
        }
        val words = t.lowercase().split(NON_WORD).filter { it.isNotEmpty() }
        if (words.all { it in FILLERS }) return ""
        return t.trim()
    }

    private val FILLERS = setOf(
        "yeah", "i", "oh", "uh", "um", "umm", "hmm", "hm", "ah", "mm", "mhm", "the", "you", "so", "and",
        "嗯", "啊", "呃", "哦",
    )

    /** 文の頭と末尾から取り除く英語のあいづち。「you」「so」などは英語の文の一部（thank you）のこともあるので含めない。 */
    private const val EN_FILLERS = "yeah|i|oh|uh|um|umm|hmm|hm|ah|mm|mhm"
    private val LEADING_FILLERS =
        Regex("^(?:\\s*(?i:$EN_FILLERS)(?![A-Za-z])[\\p{Punct}、。]*\\s*)+")
    private val TRAILING_FILLERS =
        Regex("(?:(?<![A-Za-z])(?i:$EN_FILLERS)(?![A-Za-z])[\\p{Punct}、。]*\\s*)+$")

    /** 語に分けるときの区切り（文字・数字以外）。 */
    private val NON_WORD = Regex("[^\\p{L}\\p{N}]+")
}
