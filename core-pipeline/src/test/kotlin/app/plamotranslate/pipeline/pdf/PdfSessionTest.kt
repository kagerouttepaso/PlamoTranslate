package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.SegmentUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSessionTest {
    @Test fun `今のページの未訳段落を優先し完了してから次へ進む`() {
        assertNull(PdfTranslationPlan.next(1, 4, mapOf(2 to listOf(4))))
        assertEquals(1, PdfTranslationPlan.next(1, 4, mapOf(1 to listOf(7), 2 to listOf(4))))
        assertEquals(2, PdfTranslationPlan.next(1, 4, mapOf(1 to emptyList(), 2 to listOf(4))))
        assertNull(PdfTranslationPlan.next(3, 4, mapOf(3 to emptyList(), 4 to listOf(4))))
    }

    @Test fun `画像が先に出て段落を一括で頼み訳す先の変更で古い結果を捨てる`() {
        val session = PdfSession<String>()
        session.reduce(PdfEvent.Visible(true))
        val opened = session.reduce(PdfEvent.Open("manual.pdf", "manual.pdf")).read<PdfRead.Open>()
        val rendered = session.reduce(PdfEvent.Opened(opened, 2)).read<PdfRead.Render>()
        assertEquals(0, rendered.page)
        val extracted = session.reduce(PdfEvent.Rendered(rendered, "bitmap")).read<PdfRead.Extract>()
        assertEquals("bitmap", session.state.images[0]?.content)
        val effects = session.reduce(PdfEvent.Extracted(extracted, listOf(paragraph("Cable"), paragraph("Switch"))))
        val first = effects.filterIsInstance<PdfEffect.Translate>().single()
        assertEquals(listOf("Cable", "Switch"), first.segments.map { it.text })
        assertEquals(Language.JA, first.target)
        val changed = session.reduce(PdfEvent.Target(Language.EN))
        assertTrue(PdfEffect.StopTranslation in changed)
        val second = changed.filterIsInstance<PdfEffect.Translate>().single()
        session.reduce(PdfEvent.Translated(first.batch, SegmentUpdate.Final(0, "古い訳")))
        assertTrue(session.state.translations.isEmpty())
        session.reduce(PdfEvent.Translated(second.batch, SegmentUpdate.Final(0, "Cable")))
        assertEquals(mapOf(0 to "Cable"), session.state.translations[0])
        assertFalse(session.state.opening)
    }

    @Test fun `先読み中にページを移すと残りの先読みより表示を優先する`() {
        val session = PdfSession<String>()
        session.reduce(PdfEvent.Visible(true))
        val opened = session.reduce(PdfEvent.Open("manual.pdf", "manual.pdf")).read<PdfRead.Open>()
        val first = session.reduce(PdfEvent.Opened(opened, 6)).read<PdfRead.Render>()
        val text = session.reduce(PdfEvent.Rendered(first, "first")).read<PdfRead.Extract>()
        val lookahead = session.reduce(PdfEvent.Extracted(text, emptyList())).read<PdfRead.Render>()
        assertEquals(1, lookahead.page)
        assertTrue(session.reduce(PdfEvent.Page(4)).isEmpty())
        val next = session.reduce(PdfEvent.Rendered(lookahead, "lookahead")).read<PdfRead.Render>()
        assertEquals(4, next.page)
        assertTrue(session.state.images.isEmpty())
    }

    private inline fun <reified Read : PdfRead> List<PdfEffect>.read(): Read =
        filterIsInstance<PdfEffect.Read>().map { it.request }.filterIsInstance<Read>().single()

    private fun paragraph(text: String) = PdfParagraph(text, listOf(PdfRect(0f, 0f, 1f, 1f)))
}
