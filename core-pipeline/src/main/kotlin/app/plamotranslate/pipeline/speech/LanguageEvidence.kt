package app.plamotranslate.pipeline.speech

/**
 * 判定：言語を判定した音声の窓の認識結果が、話し手の交代の根拠になる言語か（docs/設計/会話翻訳.md「カードの区切り方」）。
 * 根拠になるなら比べるための言語の組を、ならなければ null を返す。[SpeechSegmenter] は、この組が変わったときだけ
 * 話し手が代わったとみなす。
 *
 * 会話の相手が中国語でなければ（[separateChinese] が false）、中国語・広東語と判定されたものは日本語の聞き違い
 * （漢字の多い日本語）として日本語と同じ組にする。中国語の相手なら、中国語と広東語を1つの組にまとめる
 * （同じ人の話で判定が中国語と広東語の間で揺れても、カードを切らない）。
 */
object LanguageEvidence {
    /** [recognition] は後処理（[TranscriptCleanup]）の後の結果。雑音（言語が null）は根拠にならない。 */
    fun of(recognition: Recognition, separateChinese: Boolean): String? = group(recognition.lang, separateChinese)

    /** 言語のコードを、比べるための組にまとめる。 */
    fun group(lang: String?, separateChinese: Boolean): String? = when (lang) {
        "zh", "yue" -> if (separateChinese) "zh" else "ja"
        else -> lang
    }
}
