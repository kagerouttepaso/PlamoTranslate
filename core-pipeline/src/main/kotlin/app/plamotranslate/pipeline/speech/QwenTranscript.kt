package app.plamotranslate.pipeline.speech

object QwenTranscript {
    val names = mapOf(
        "zh" to "Chinese", "en" to "English", "yue" to "Cantonese", "ar" to "Arabic", "de" to "German",
        "fr" to "French", "es" to "Spanish", "pt" to "Portuguese", "id" to "Indonesian", "it" to "Italian",
        "ko" to "Korean", "ru" to "Russian", "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese",
        "tr" to "Turkish", "hi" to "Hindi", "ms" to "Malay", "nl" to "Dutch", "sv" to "Swedish",
        "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech", "tl" to "Filipino",
        "fa" to "Persian", "el" to "Greek", "hu" to "Hungarian", "mk" to "Macedonian", "ro" to "Romanian",
    )
    private val codes = names.entries.associate { it.value.lowercase() to it.key }
    private val roleWords = setOf("system", "user", "assistant", "システム", "시스템")
    private val nonWord = Regex("[^\\p{L}\\p{N}]+")

    fun parse(raw: String, specified: String?): Recognition {
        val output = raw.substringBefore("<|").trim()
        val separator = "<asr_text>"
        val header = output.substringBefore(separator, "").trim()
        val code = if (header.startsWith("language ")) codes[header.removePrefix("language ").trim().lowercase()] else null
        val body = output.substringAfter(separator).trim()
        val words = body.lowercase().split(nonWord).filter { it.isNotEmpty() }
        val roleOnly = words.any { it in roleWords } && words.all { it in roleWords || it == "the" }
        return Recognition(if (roleOnly) "" else body, specified ?: code, source = when {
            specified != null -> LanguageSource.SPECIFIED
            code != null -> LanguageSource.JUDGED
            else -> LanguageSource.GUESSED
        })
    }
}
