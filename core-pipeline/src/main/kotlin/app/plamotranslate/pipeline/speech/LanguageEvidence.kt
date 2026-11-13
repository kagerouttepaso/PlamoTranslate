package app.plamotranslate.pipeline.speech

/**
 * 会話の言語（話し手の交代の根拠に使う言語）。[groups] は [LanguageEvidence.group] の組（"ja"・"ko"・"en"・"zh"）で、
 * null なら言語を絞らない。[separateChinese] は、中国語・広東語を日本語と別の言語として扱うか（自分か相手が中国語のとき）。
 */
data class SpeechLanguages(val groups: Set<String>? = null, val separateChinese: Boolean = false) {
    companion object {
        val ANY = SpeechLanguages()
    }
}

/**
 * 判定：言語を判定した音声の窓の認識結果が、話し手の交代の根拠になる言語か（docs/設計/会話翻訳.md「カードの区切り方」）。
 * 根拠になるなら比べるための言語の組を、ならなければ null を返す。[SpeechSegmenter] は、この組が変わったときだけ
 * 話し手が代わったとみなす。根拠にするのは、次をすべて満たすときだけ（作者の会話の録音で、言語の交代での区切り8回のうち
 * 7回が、これを満たさない判定から起きていた。`docs/判断/2026-11-13_会話翻訳の話し手の交代は確かな言語の判定だけで区切る.md`）。
 * - 判定した言語と、認識した文字の種類が合う（韓国語ならハングル、英語なら英字、日本語なら仮名か漢字、中国語なら漢字）。
 *   「ま」を韓国語、「我」を英語と判定したものは、聞き違い。
 * - 中身が1語より多い（英語は2語以上、ほかは3文字以上）。話の間の「Now.」「No.」のような1語の判定は、雑音や言いよどみの聞き違いが多い。
 * - 会話の言語（[SpeechLanguages.groups]）のどれかである。
 *
 * 会話の相手が中国語でなければ、中国語・広東語と判定されたものは日本語の聞き違い（漢字の多い日本語）として日本語と同じ組にする。
 * 中国語の相手なら、中国語と広東語を1つの組にまとめる（同じ人の話で判定が揺れても、カードを切らない）。
 */
object LanguageEvidence {
    /** [recognition] は聞き違いの判定（[TranscriptCleanup]）の後の結果。雑音（言語が null）は根拠にならない。 */
    fun of(recognition: Recognition, languages: SpeechLanguages): String? {
        val lang = recognition.lang ?: return null
        val group = group(lang, languages.separateChinese) ?: return null
        if (languages.groups != null && group !in languages.groups) return null
        val letters = Letters.of(recognition.text)
        if (!letters.matches(lang)) return null
        if (!letters.enough(lang)) return null
        return group
    }

    /** 言語のコードを、比べるための組にまとめる（根拠かどうかは見ない。言語の継ぎ目探しはこれで比べる）。 */
    fun group(lang: String?, separateChinese: Boolean): String? = when (lang) {
        "zh", "yue" -> if (separateChinese) "zh" else "ja"
        else -> lang
    }

    /** 文字の種類ごとの数と、英字の語の数。 */
    private class Letters(val hangul: Int, val latin: Int, val kana: Int, val han: Int, val latinWords: Int) {
        val total get() = hangul + latin + kana + han

        /** 判定した言語の文字が、文字の半分以上を占めるか。 */
        fun matches(lang: String): Boolean = when (lang) {
            "ko" -> hangul * 2 >= total
            "en" -> latin * 2 >= total
            "ja" -> (kana + han) * 2 >= total
            "zh", "yue" -> han * 2 >= total && kana == 0
            else -> false
        }

        fun enough(lang: String): Boolean = if (lang == "en") latinWords >= MIN_WORDS else total >= MIN_CHARS

        companion object {
            fun of(text: String): Letters {
                var hangul = 0
                var latin = 0
                var kana = 0
                var han = 0
                for (c in text) when {
                    c in '가'..'힣' || c in 'ㄱ'..'ㆎ' -> hangul++
                    c in 'A'..'Z' || c in 'a'..'z' -> latin++
                    c in 'ぁ'..'ヿ' -> kana++
                    Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> han++
                }
                val words = text.split(NON_LATIN).count { it.isNotEmpty() }
                return Letters(hangul, latin, kana, han, words)
            }

            private val NON_LATIN = Regex("[^A-Za-z]+")
        }
    }

    /** 英語の判定を根拠にする語の数。 */
    private const val MIN_WORDS = 2

    /** 英語以外の判定を根拠にする文字の数（仮名・漢字・ハングル）。 */
    private const val MIN_CHARS = 3
}
