package app.plamotranslate.pipeline.pdf

/** 画面と保存した PDF のしおりが同じ本文の訳を使う。 */
object PdfTitles {
    fun titleKey(title: String): String = plainPdfText(title).lowercase()

    fun titleTranslations(paragraphs: Iterable<PdfParagraph>, translations: Map<String, String>): Map<String, String> =
        buildMap {
            for (paragraph in paragraphs) {
                val translation = translations[paragraph.text] ?: continue
                val pieces = PdfMarkupTranslation.prepare(paragraph, translation) ?: continue
                put(titleKey(paragraph.text), normalizePdfText(pieces.joinToString("") { it.text }))
            }
        }
}
