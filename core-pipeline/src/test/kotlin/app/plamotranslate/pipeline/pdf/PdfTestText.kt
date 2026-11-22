package app.plamotranslate.pipeline.pdf

internal val testPageSize = PdfPageSize(600.0, 800.0)

internal data class TestLine(
    val text: String,
    val x: Double = 20.0,
    val y: Double = 700.0,
    val size: Double = 10.0,
    val bold: Boolean = false,
    val path: List<Int>? = null,
)

internal fun testPage(vararg lines: TestLine): PdfPageText {
    val glyphs = buildList {
        for ((number, line) in lines.withIndex()) {
            var x = line.x
            for (cp in line.text.codePoints().toArray()) {
                val advance = line.size * 0.45
                add(PdfGlyph(size, String(Character.toChars(cp)), x, line.y,
                    PdfBounds(x, line.y - 2, x + advance, line.y + line.size * 0.8),
                    if (line.bold) "Test-Bold" else "Test", if (line.bold) 700 else 400, PdfColor.Black,
                    line.path ?: listOf(number), line.size))
                x += advance
            }
        }
    }
    return PdfPageText(testPageSize, glyphs)
}

internal fun testParagraphs(vararg lines: TestLine) = PdfParagraphs.page(testPage(*lines))
