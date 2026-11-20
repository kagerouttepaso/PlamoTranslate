package app.plamotranslate.pipeline.speech

/**
 * 会話の言語（話し手の交代の根拠に使う言語）。[groups] は [LanguageEvidence.group] の組（"ja"・"ko"・"en"・"zh"）で、
 * null なら言語を絞らない。[separateChinese] は、中国語・広東語を日本語と別の言語として扱うか（自分か相手が中国語のとき）。
 * [primary] は、言語の手がかりが何も無いときに使う言語（会話翻訳では自分の言語の組）。言語を指定せずに書き起こすと、
 * 会話に無い言語（タイ語など）の文を作り出すことがあるため。
 */
data class SpeechLanguages(val groups: Set<String>? = null, val separateChinese: Boolean = false, val primary: String? = null) {
    companion object {
        val ANY = SpeechLanguages()
    }
}

/**
 * 判定：言語を判定した音声の区間の認識結果が、話し手の交代の根拠になる言語か（docs/設計/会話翻訳.md「カードの区切り方」）。
 * 根拠になるなら比べるための言語の組を、ならなければ null を返す。[SpeechSegmenter] は、この組が変わったときだけ
 * 話し手が代わったとみなす。根拠にするのは、次をすべて満たすときだけ（作者の会話の録音で、言語の交代での区切り8回のうち
 * 7回が、これを満たさない判定から起きていた。`docs/判断/2026-11-13_会話翻訳の話し手の交代は確かな言語の判定だけで区切る.md`）。
 * - 判定した言語の文字が、認識した文字の半分以上を占める（韓国語ならハングル、英語なら英字、日本語なら仮名か漢字、
 *   中国語なら漢字。[ScriptCounts.fits]）。「ま」を韓国語、「我」を英語と判定したものは、聞き違い。
 * - 中身が1語より多い（ラテン文字の言語は2語以上、ほかは3文字以上）。話の間の「Now.」「No.」のような1語の判定は、雑音や言いよどみの聞き違いが多い。
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
        val counts = ScriptCounts.of(recognition.text)
        if (!counts.fits(lang) || !enough(counts, lang)) return null
        return group
    }

    /** 中身が1語より多いか（ラテン文字の言語は2語以上、ほかは3文字以上）。 */
    private fun enough(counts: ScriptCounts, lang: String): Boolean =
        if (Script.ofLanguage(lang) == Script.LATIN) counts.latinWords >= MIN_WORDS else counts.letters >= MIN_CHARS

    /** 言語のコードを、比べるための組にまとめる（根拠かどうかは見ない。言語の境界探しはこれで比べる）。 */
    fun group(lang: String?, separateChinese: Boolean): String? = when (lang) {
        "zh", "yue" -> if (separateChinese) "zh" else "ja"
        else -> lang
    }

    /** ラテン文字の言語の判定を根拠にする語の数。 */
    private const val MIN_WORDS = 2

    /** ラテン文字以外の言語の判定を根拠にする文字の数。 */
    private const val MIN_CHARS = 3
}
