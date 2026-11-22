package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.SegmentUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSessionTest {
    @Test fun `開いただけでは描画し開始後に全ページを読み取って訳す`() {
        val session = PdfSession<String>()
        session.reduce(PdfEvent.Visible(true))
        val opened = session.reduce(PdfEvent.Open("manual.pdf", "manual.pdf")).read<PdfRead.Open>()
        val first = session.reduce(PdfEvent.Opened(opened, 2)).read<PdfRead.Render>()
        assertEquals(0, first.page)
        val shown = session.reduce(PdfEvent.Rendered(first, "first"))
        assertEquals(1, shown.read<PdfRead.Render>().page)
        assertEquals(PdfPhase.Idle, session.state.phase)
        assertEquals("first", session.state.images[0]?.content)
        assertTrue(session.reduce(PdfEvent.Start).isEmpty())
        val extracted = session.reduce(PdfEvent.Rendered(shown.read(), "second")).read<PdfRead.Extract>()
        val next = session.reduce(PdfEvent.Extracted(extracted, testPage(TestLine("Read this."))))
        assertTrue(next.translations().isEmpty())
        assertEquals(1, next.read<PdfRead.Extract>().page)
        val ready = session.reduce(PdfEvent.Extracted(next.read(), testPage(TestLine("Continue here."))))
        assertEquals("Read this.", ready.translations().single().segments.single().text)
    }

    @Test fun `バッチの返事が逆順でも段落がそろったページは一回だけ書く`() {
        val session = PdfSession<String>()
        val batches = session.start(3,
            testPage(TestLine("First paragraph."), TestLine("Shared paragraph.", y = 680.0)),
            testPage(TestLine("Shared paragraph.", y = 680.0), TestLine("Last paragraph.", y = 660.0)),
        )
        val (first, shared, last) = batches
        assertEquals(listOf("First paragraph.", "Shared paragraph.", "Last paragraph."),
            batches.map { it.segments.single().text })
        assertTrue(session.finish(last, "最後の段落。").writes().isEmpty())
        val page1 = session.finish(shared, "共通の段落。").writes().single()
        assertEquals(1, page1.page)
        assertTrue(session.state.writtenPages.isEmpty())
        session.reduce(PdfEvent.Written(page1.document, page1.page, true))
        assertEquals(setOf(1), session.state.writtenPages)
        assertTrue(session.finish(shared, "重複した訳").writes().isEmpty())
        val page0 = session.finish(first, "最初の段落。").writes().single()
        assertEquals(0, page0.page)
        assertEquals(mapOf("First paragraph." to "最初の段落。", "Shared paragraph." to "共通の段落。"), page0.translations)
        session.reduce(PdfEvent.Written(page0.document, page0.page, true))
        assertEquals(setOf(0, 1), session.state.writtenPages)
        assertEquals(PdfPhase.Finished, session.state.phase)
    }

    @Test fun `中止で古い途中経過と返事を捨て確定した段落を残して再開する`() {
        val session = PdfSession<String>()
        val (first, second, third) = session.start(3,
            testPage(TestLine("First paragraph."), TestLine("Second paragraph.", y = 680.0), TestLine("Third paragraph.", y = 660.0)),
        )
        session.finish(first, "最初の段落。")
        session.reduce(PdfEvent.Translated(second.batch, SegmentUpdate.Partial(second.segments.single().id, "途中です。")))
        assertEquals("途中です。", session.state.previews["Second paragraph."]?.text)
        assertTrue(PdfEffect.StopTranslation in session.reduce(PdfEvent.Stop))
        assertEquals(PdfPhase.Paused, session.state.phase)
        assertTrue(session.state.working.isEmpty())
        assertTrue(session.state.previews.isEmpty())
        val resumed = session.reduce(PdfEvent.Start).translations()
        assertEquals(listOf("Second paragraph.", "Third paragraph."), resumed.flatMap { it.segments.map { segment -> segment.text } })
        session.reduce(PdfEvent.Translated(second.batch, SegmentUpdate.Partial(second.segments.single().id, "古い途中経過。")))
        session.finish(third, "古い返事。")
        assertTrue(session.state.previews.isEmpty())
        assertEquals(mapOf(0 to "最初の段落。"), session.state.translations[0])
        session.finish(resumed[0], "二つ目の段落。")
        val write = session.finish(resumed[1], "三つ目の段落。").writes().single()
        session.reduce(PdfEvent.Written(write.document, write.page, true))
        assertEquals(PdfPhase.Finished, session.state.phase)
    }

    @Test fun `表示ページを移しても実行中の依頼を残し空いた枠を新しいページに使う`() {
        val session = PdfSession<String>()
        val pages = (0..3).map { testPage(TestLine("Paragraph $it.", y = 700.0 - it * 2)) }
        val initial = session.start(1, *pages.toTypedArray()).single()
        assertTrue(session.reduce(PdfEvent.Page(3)).isEmpty())
        val after = session.finish(initial, "先頭。")
        assertFalse(PdfEffect.StopTranslation in after)
        assertEquals("Paragraph 3.", after.translations().single().segments.single().text)
    }

    @Test fun `別の文書を開くと翻訳を止め古い文書の書き込みと訳を捨てる`() {
        val session = PdfSession<String>()
        val (first, second) = session.start(2, testPage(TestLine("First paragraph.")), testPage(TestLine("Last paragraph.")))
        val pending = session.finish(first, "先頭。").writes().single()
        val replacement = session.reduce(PdfEvent.Open("new.pdf", "new.pdf"))
        assertTrue(PdfEffect.StopTranslation in replacement)
        assertTrue(session.reduce(PdfEvent.Opened(replacement.read(), 1)).isEmpty())
        session.reduce(PdfEvent.Written(pending.document, pending.page, true))
        session.finish(second, "古い訳。")
        assertTrue(session.state.translations.isEmpty())
        assertTrue(session.state.writtenPages.isEmpty())
        assertEquals("new.pdf", session.state.name)
        assertEquals(PdfPhase.Idle, session.state.phase)
        assertTrue(session.reduce(PdfEvent.Target(Language.EN)).isEmpty())
    }

    @Test fun `失敗は再開待ちにし成功していた段落の訳を保持する`() {
        val session = PdfSession<String>()
        val (first, second) = session.start(2,
            testPage(TestLine("First paragraph."), TestLine("Second paragraph.", y = 680.0)))
        session.finish(first, "先頭。")
        val failed = session.reduce(PdfEvent.Translated(second.batch, SegmentUpdate.Failed(second.segments.single().id, "失敗")))
        assertTrue(PdfEffect.StopTranslation in failed)
        assertEquals(PdfPhase.Paused, session.state.phase)
        assertEquals(setOf(1), session.state.untranslated[0])
        assertEquals(listOf("Second paragraph."), session.reduce(PdfEvent.Retry).translations().single().segments.map { it.text })
    }

    private fun PdfSession<String>.start(slots: Int, vararg pages: PdfPageText): List<PdfEffect.Translate> {
        reduce(PdfEvent.Parallel(slots))
        val open = reduce(PdfEvent.Open("test.pdf", "test.pdf")).read<PdfRead.Open>()
        assertTrue(reduce(PdfEvent.Opened(open, pages.size)).isEmpty())
        var effects = reduce(PdfEvent.Start)
        for ((index, text) in pages.withIndex()) {
            val extract = effects.read<PdfRead.Extract>()
            assertEquals(index, extract.page)
            effects = reduce(PdfEvent.Extracted(extract, text))
        }
        return effects.translations()
    }

    private fun PdfSession<String>.finish(effect: PdfEffect.Translate, text: String): List<PdfEffect> =
        reduce(PdfEvent.Translated(effect.batch, SegmentUpdate.Final(effect.segments.single().id, text)))

    private inline fun <reified Read : PdfRead> List<PdfEffect>.read(): Read =
        filterIsInstance<PdfEffect.Read>().map { it.request }.filterIsInstance<Read>().single()

    private fun List<PdfEffect>.translations() = filterIsInstance<PdfEffect.Translate>()
    private fun List<PdfEffect>.writes() = filterIsInstance<PdfEffect.WritePage>()
}
