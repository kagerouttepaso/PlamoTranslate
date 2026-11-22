package app.plamotranslate.pipeline.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfParagraphsTest {
    @Test fun `表のセルを独立した段落として訳す`() {
        val paragraph = testParagraphs(
            TestLine("Input", y = 712.0), TestLine("Type", x = 170.0, y = 712.0),
            TestLine("Power"), TestLine("Voltage", x = 170.0),
        )
        assertEquals(listOf("Input", "Type", "Power", "Voltage"), paragraph.map { it.plain })
        assertEquals(166.0, paragraph[0].right, 0.0)
        val plan = PdfTranslationPlan(PdfParagraphs.classify(mapOf(0 to paragraph)))
        assertEquals(listOf(listOf("Input", "Type", "Power", "Voltage")), plan.batches(0, 4))
    }

    @Test fun `目次の点線と番号は共有オブジェクトのまま独立させる`() {
        val path = listOf(2, 1, 4)
        val paragraphs = testParagraphs(
            TestLine("Section 😀 . . . .", path = path),
            TestLine("12", x = 200.0, path = path),
        )
        val all = PdfParagraphs.classify(mapOf(0 to paragraphs)).getValue(0)
        assertEquals(listOf("Section 😀", ". . . .", "12"), all.map { it.text })
        assertEquals(listOf(null, PdfSkip.NoWords, PdfSkip.NoWords), all.map { it.skip })
        assertTrue(all.all { it.objectPaths == listOf(path) })
        assertEquals(". . . .".replace(" ", ""), all[1].keptGlyphs.joinToString("") { it.text })
        assertEquals("12", all[2].keptGlyphs.joinToString("") { it.text })
        assertEquals(0..8, all[0].characterRange)
    }

    @Test fun `箇条書きの記号と行末ハイフンを保持し左端のそろう続きの行をつなぐ`() {
        val page = testPage(TestLine("• Power-"), TestLine("supply", x = 29.0, y = 688.0), TestLine("• Cable", y = 676.0))
        val marked = page.copy(glyphs = page.glyphs.map { if (it.text == "-") it.copy(hyphen = true) else it })
        val (first, next) = PdfParagraphs.page(marked)
        assertEquals("Powersupply", first.text)
        assertEquals("Cable", next.text)
        assertEquals(listOf("•", "-"), first.keptGlyphs.map { it.text })
        assertEquals(listOf(700.0, 688.0), first.lineDetails.map { it.baseline })
        assertEquals(29.0, first.x, 0.0)
        assertEquals(20.0, first.bounds.left, 0.0)
        assertEquals(29.0, first.translationBounds!!.left, 0.0)
        assertEquals(listOf(listOf(0), listOf(1)), first.objectPaths)
        assertEquals(12.0, first.leading, 0.0)
        assertFalse(first.glyphs.first().translated)
    }

    @Test fun `同じ基線の上付き字は含め大きさ太さ左端の違いは段落を分ける`() {
        val page = testPage(TestLine("Power2"), TestLine("Bold", y = 688.0, bold = true),
            TestLine("Large", y = 676.0, size = 11.0), TestLine("Indented", x = 24.0, y = 664.0, size = 11.0))
        val glyphs = page.glyphs.map { if (it.index == 5) it.copy(size = 6.0, originY = 704.0) else it }
        val paragraphs = PdfParagraphs.page(page.copy(glyphs = glyphs))
        assertEquals(listOf("Power2", "Bold", "Large", "Indented"), paragraphs.map { it.text })
        assertEquals(10.0, paragraphs.first().size, 0.0)
    }

    @Test fun `異なる三ページのヘッダーとフッターを数字を無視して除く`() {
        val pages = (0..2).associateWith { page ->
            testParagraphs(
                TestLine("Manual ${page + 1}", y = 780.0 + 0.1 * page),
                TestLine("Read this.", y = 700.0 - page * 10),
                TestLine("Page ${page + 10}", y = 22.0),
                TestLine("日本語です", y = 50.0 + page * 2),
                TestLine("3-14", x = 100.0, y = 30.0 + page * 2),
            )
        }
        for (paragraphs in PdfParagraphs.classify(pages).values) {
            assertEquals(listOf(PdfSkip.Repeat, null, PdfSkip.Japanese, PdfSkip.NoWords, PdfSkip.Repeat),
                paragraphs.map { it.skip })
        }
        val samePage = PdfParagraphs.classify(mapOf(0 to (1..3).map { TestLine("Manual $it", x = it * 80.0) }
            .let { testParagraphs(*it.toTypedArray()) }))
        assertTrue(samePage.getValue(0).all { it.skip == null })
    }

    @Test fun `リンクと太字と等幅のマーカーを復元し原文と同じ訳を残す`() {
        val source = testPage(TestLine("See port code"))
        val paragraph = PdfParagraphs.page(source.copy(glyphs = source.glyphs.map {
            when (it.index) {
                in 4..7 -> it.copy(font = "Test-Black", link = 5)
                in 9..12 -> it.copy(font = "Courier New")
                else -> it
            }
        })).single()
        assertEquals("See <a1>port</a1> <c2>code</c2>", paragraph.text)
        assertEquals("See port", paragraph.plain)
        assertEquals(listOf(PdfPiece("接続先", true, 5), PdfPiece("の", false), PdfPiece("code")),
            PdfMarkupTranslation.prepare(paragraph, "<a1>接続先</a1>の<c2>変えてはいけない</c2>"))
        assertEquals(listOf(PdfPiece("接続先")), PdfMarkupTranslation.pieces(paragraph, "<a1>接続先"))
        assertNull(PdfMarkupTranslation.prepare(paragraph, " See <a1>port</a1> "))
        assertNull(PdfMarkupTranslation.prepare(paragraph, " "))
        val whole = PdfParagraphs.page(source.copy(glyphs = source.glyphs.map { it.copy(link = 7) })).single()
        assertEquals("See port code", whole.text)
        assertEquals(7, whole.whole?.link)
        assertEquals(4, pdfGlyphLink(source.glyphs.first().bounds, listOf(
            PdfLinkAnnotation(4, PdfBounds(22.7, 696.0, 36.0, 710.0)),
            PdfLinkAnnotation(5, PdfBounds(20.0, 696.0, 36.0, 710.0)),
        )))
    }
}
