package app.plamotranslate.pipeline.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfLayoutTest {
    private val measure: PdfMeasure = { text, _, size -> text.length * size }

    @Test fun `途中表示が原文の残りを足しても縮めた訳文の配置を保つ`() {
        val line = PdfParagraphLine(PdfBounds(10.0, 48.0, 40.0, 58.0), 50.0, 0..0)
        val glyph = testPage(TestLine("A")).glyphs.single().copy(bounds = line.bounds)
        val paragraph = PdfParagraph("Firmware", "Firmware", testPageSize, listOf(line), listOf(PdfParagraphGlyph(glyph, true)),
            10.0, 59.0, 50.0, 50.0, 12.0, 10.0, true, PdfColor.Black)
        val geometry = PdfLayout.paragraphGeometry(paragraph, listOf(paragraph))
        val translation = listOf(PdfPiece("ファームウェア", bold = true))
        val result = PdfLayout.planParagraph(geometry, translation, measure)
        val preview = PdfLayout.planParagraph(geometry,
            translation + PdfPiece(" Remaining source", bold = true, source = true), measure)
        assertEquals(7.0, result.size, 0.0)
        assertEquals("ファームウェア", result.lines.first().runs.first().text)
        assertEquals(result, preview)
    }

    @Test fun `英数を分断せず行頭禁則をぶら下げ行末禁則を次の行に送る`() {
        val geometry = PdfParagraphGeometry(false, PdfBounds(0.0, 0.0, 3.0, 10.0), 0.0, 10.0, 3.0, 0.0, 1.0, 1.0)
        fun wrapped(text: String, width: Double = 3.0) =
            PdfLayout.planParagraph(geometry.copy(width = width), listOf(PdfPiece(text)), measure).lines
                .map { line -> line.runs.joinToString("") { it.text } }
        assertEquals(listOf("日本語、", "です。"), wrapped("日本語、です。"))
        assertEquals(listOf("これ", "「次」"), wrapped("これ「次」"))
        assertEquals(listOf("値は", "USB-123", "です"), wrapped("値は USB-123 です", 4.0))
        assertEquals(listOf("😀😀", "東"), PdfLayout.planParagraph(geometry.copy(width = 2.0),
            listOf(PdfPiece("😀😀東"))) { text, _, size -> text.codePointCount(0, text.length) * size }
            .lines.map { it.runs.single().text })
    }

    @Test fun `下の段落とフォームの幅を使い文字と行送りを同じ割合で縮める`() {
        val paragraph = testParagraphs(TestLine("Label", x = 20.0, y = 70.0, path = listOf(0, 1))).single().copy(right = 300.0)
        val below = testParagraphs(TestLine("Lower", x = 20.0, y = 53.0)).single()
        val elsewhere = testParagraphs(TestLine("Right", x = 350.0, y = 66.0)).single()
        val geometry = PdfLayout.paragraphGeometry(paragraph, listOf(paragraph, below, elsewhere))
        assertTrue(geometry.masked)
        assertEquals(64.0, geometry.floor, 0.0)
        assertEquals(22.5 * 1.15 + .5, geometry.width, 0.0)
        val result = PdfLayout.planParagraph(geometry.copy(width = 23.6, floor = 69.0), listOf(PdfPiece("東京都")), measure)
        assertEquals(7.5, result.size, 0.0)
        assertEquals(paragraph.leading * 0.75, result.leading, 0.0)
        assertTrue(result.shrunk)
        val overflow = PdfLayout.planParagraph(geometry.copy(width = 9.0, floor = 69.0), listOf(PdfPiece("東京都")), measure)
        assertEquals(7.0, overflow.size, 0.0)
        assertEquals(3, overflow.lines.size)
        assertFalse(overflow.lines.last().baseline >= geometry.floor)
    }

    @Test fun `同じ書式の訳文をまとめリンクの基線と幅を保持する`() {
        val geometry = PdfParagraphGeometry(false, PdfBounds(0.0, 0.0, 9.0, 9.0), 5.0, 12.0, 9.0, 0.0, 1.0, 1.0)
        val pieces = listOf(PdfPiece("甲", bold = true, link = 1), PdfPiece("乙", bold = true, link = 1), PdfPiece("丙"))
        val line = PdfLayout.planParagraph(geometry, pieces, measure).lines.single()
        assertEquals(12.0, line.baseline, 0.0)
        assertEquals(listOf(PdfRun("甲乙", 2.0, 5.0, true, 1, false), PdfRun("丙", 1.0, 7.0, false, null, false)), line.runs)
    }
}
