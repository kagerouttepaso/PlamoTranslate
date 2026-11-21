package app.plamotranslate.pipeline.pdf

import org.junit.Assert.assertEquals
import org.junit.Test

class PdfLayoutTest {
    @Test fun `上に寄ったアポストロフィを表題から分離しない`() {
        val title = line("Users Guide", 0.05f, 0.10f).toMutableList()
        title.add(PdfGlyph("’", PdfRect(0.088f, 0.099f, 0.09f, 0.104f)))
        assertEquals(listOf("User’s Guide"), layout(title).map { it.text })
    }

    @Test fun `箇条書きの折り返しはつなげて次の項目と図の説明を分ける`() {
        val layout = layout(
            line("1. Connect the USB cable", 0.10f, 0.10f),
            line("to the computer.", 0.12f, 0.122f),
            line("2. Open the application.", 0.10f, 0.144f),
            line("Figure 1: USB connector", 0.10f, 0.166f),
        )
        assertEquals(listOf(
            "1. Connect the USB cable to the computer.", "2. Open the application.", "Figure 1: USB connector",
        ), layout.map { it.text })
        assertEquals(2, layout.first().lines.size)
    }

    @Test fun `表は左から右へ行単位で読み各セルを分ける`() {
        val layout = layout(
            line("Pin", 0.10f, 0.10f), line("Signal", 0.45f, 0.10f),
            line("1", 0.10f, 0.122f), line("Supply input", 0.45f, 0.122f),
            line("2", 0.10f, 0.144f), line("Shield", 0.45f, 0.144f),
        )
        assertEquals(listOf("Pin", "Signal", "1", "Supply input", "2", "Shield"), layout.map { it.text })
    }

    @Test fun `段組みは左の段を読み終えてから右の段を読む`() {
        val layout = layout(
            line("Left column starts here", 0.05f, 0.10f), line("Right column starts here", 0.56f, 0.10f),
            line("and continues to the end.", 0.05f, 0.122f), line("and continues to the end.", 0.56f, 0.122f),
        )
        assertEquals(listOf(
            "Left column starts here and continues to the end.", "Right column starts here and continues to the end.",
        ), layout.map { it.text })
    }

    private fun layout(vararg lines: List<PdfGlyph>) = PdfLayout.paragraphs(lines.flatMap { it }.reversed(), 1f)

    private fun line(text: String, left: Float, top: Float): List<PdfGlyph> = text.mapIndexedNotNull { index, char ->
        val x = left + index * 0.010f
        if (char == ' ') null else PdfGlyph(char.toString(), PdfRect(x, top, x + 0.008f, top + 0.016f),
            spaceBefore = index > 0 && text[index - 1] == ' ')
    }
}
