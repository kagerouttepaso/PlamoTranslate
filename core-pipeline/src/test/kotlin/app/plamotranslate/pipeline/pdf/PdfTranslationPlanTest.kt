package app.plamotranslate.pipeline.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfTranslationPlanTest {
    @Test fun `表示ページから距離順に選び前のページの距離は一倍半にする`() {
        val pages = (0..8).associateWith { n -> testParagraphs(TestLine("Paragraph $n.")) }
        val plan = PdfTranslationPlan(pages)
        assertEquals(listOf(4, 5, 3, 6, 2, 7, 8, 1, 0).map { listOf("Paragraph $it.") }, plan.batches(4, 9))
        assertEquals(listOf(listOf("Paragraph 7."), listOf("Paragraph 8.")), plan.batches(7, 2, setOf("Paragraph 4.")))
    }

    @Test fun `文字数の多い本文を基準に見出しと第1段落を先にし短い項目は分割しない`() {
        val paragraphs = testParagraphs(
            TestLine("First body paragraph.", y = 750.0),
            TestLine("Further details occupy the ordinary font.", y = 725.0),
            TestLine("Connections", y = 700.0, size = 12.0),
            TestLine("Input", y = 675.0), TestLine("Output", x = 180.0, y = 675.0),
            TestLine("Read the next paragraph.", y = 650.0),
            TestLine("Last body paragraph.", y = 625.0),
        )
        val plan = PdfTranslationPlan(mapOf(0 to paragraphs))
        assertEquals(listOf(
            listOf("Connections", "First body paragraph."),
            listOf("Read the next paragraph."),
            listOf("Further details occupy the ordinary font.", "Last body paragraph."),
            listOf("Input", "Output"),
        ), plan.batches(0, 4))
    }

    @Test fun `八行四百字で切り上限を超える段落は単独にする`() {
        val small = (1..9).map { TestLine("Paragraph $it.", y = 700.0 - it * 20) }
        val long = "Long ".repeat(81) + "paragraph."
        val source = testParagraphs(*(small + TestLine(long, y = 500.0)).toTypedArray())
        val plan = PdfTranslationPlan(mapOf(0 to source))
        val batches = plan.batches(0, 3)
        assertEquals(listOf(8, 1, 1), batches.map { it.size })
        assertEquals(listOf(long), batches.last())
        val two = (1..2).map { i -> TestLine("Line $i " + "word ".repeat(40) + "end.", y = 700.0 - i * 20) }
        val chars = PdfTranslationPlan(mapOf(0 to testParagraphs(*two.toTypedArray())))
        assertEquals(listOf(1, 1), chars.batches(0, 2).map { it.size })
    }

    @Test fun `後続ページも今回空いた枠数で分割し入りきらない分は次に選ぶ`() {
        val next = (1..4).map { TestLine("Next paragraph $it.", y = 750.0 - it * 25) }
        val plan = PdfTranslationPlan(mapOf(0 to testParagraphs(TestLine("Focus.")), 1 to testParagraphs(*next.toTypedArray())))
        assertEquals(listOf(listOf("Focus."), listOf("Next paragraph 1.")), plan.batches(0, 3).take(2))
        assertEquals(listOf("Next paragraph 2."), plan.batches(0, 3).last())
    }

    @Test fun `同じ原文をページ横断で共有し必要な原文が全てそろったページを返す`() {
        val shared = TestLine("Shared paragraph.", y = 680.0)
        val plan = PdfTranslationPlan(mapOf(
            0 to testParagraphs(TestLine("First paragraph."), shared),
            1 to testParagraphs(shared, TestLine("Last paragraph.", y = 660.0)),
            2 to emptyList(),
        ))
        assertEquals(listOf("First paragraph.", "Shared paragraph.", "Last paragraph."), plan.batches(0, 3).flatten())
        assertFalse(plan.isPageComplete(0, setOf("Shared paragraph.")))
        assertTrue(plan.isPageComplete(1, setOf("Shared paragraph.", "Last paragraph.")))
        assertEquals(listOf(1), plan.completedPages(listOf("Shared paragraph."), setOf("Shared paragraph.", "Last paragraph.")))
        assertFalse(plan.isPageComplete(2, plan.sources))
    }
}
