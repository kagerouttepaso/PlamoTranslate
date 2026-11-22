package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.SegmentUpdate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfSessionTest {
    @Test fun `並列翻訳中は表示位置に最も近い処理中のページを状態に出す`() {
        val session = PdfSession()
        val pages = listOf("First.", "Second.", "Third.", "Fourth.").map { testPage(TestLine(it)) }
        val batches = session.start(2, *pages.toTypedArray())
        assertEquals(2, batches.size)
        session.reduce(PdfEvent.Page(1))
        assertEquals(1, session.state.translationPage)
        session.reduce(PdfEvent.Page(3))
        assertEquals(1, session.state.translationPage)
        session.finish(batches.first(), "最初。")
        assertEquals(3, session.state.translationPage)
        session.reduce(PdfEvent.Stop)
        assertNull(session.state.translationPage)
    }

    @Test fun `開いただけでは訳さずビューアの状態に関係なく開始後に全ページを読む`() {
        val session = PdfSession()
        val opened = session.reduce(PdfEvent.Open("manual.pdf", "manual.pdf")).read<PdfRead.Open>()
        assertTrue(session.reduce(PdfEvent.Opened(opened, 2)).isEmpty())
        assertEquals(PdfPhase.Idle, session.state.phase)
        val extracted = session.reduce(PdfEvent.Start).read<PdfRead.Extract>()
        assertEquals(0, extracted.page)
        val next = session.reduce(PdfEvent.Extracted(extracted, testPage(TestLine("Read this."))))
        assertTrue(next.translations().isEmpty())
        assertEquals(1, next.read<PdfRead.Extract>().page)
        val ready = session.reduce(PdfEvent.Extracted(next.read(), testPage(TestLine("Continue here."))))
        assertEquals("Read this.", ready.translations().single().segments.single().text)
    }

    @Test fun `バッチの返事が逆順でも段落がそろったページは一回だけ書く`() {
        val session = PdfSession()
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
        val session = PdfSession()
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
        assertTrue(session.state.pageMarks(0).isEmpty())
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
        val session = PdfSession()
        val pages = (0..3).map { testPage(TestLine("Paragraph $it.", y = 700.0 - it * 2)) }
        val initial = session.start(1, *pages.toTypedArray()).single()
        assertTrue(session.reduce(PdfEvent.Page(3)).isEmpty())
        val after = session.finish(initial, "先頭。")
        assertFalse(PdfEffect.StopTranslation in after)
        assertEquals("Paragraph 3.", after.translations().single().segments.single().text)
    }

    @Test fun `別の文書を開くと翻訳を止め古い文書の書き込みと訳を捨てる`() {
        val session = PdfSession()
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

    @Test fun `差し替える段落が無いページは原文のまま終了する`() {
        val session = PdfSession()
        val batch = session.start(1, testPage(TestLine("PD-100 AC"))).single()
        assertTrue(session.finish(batch, "PD-100 AC").writes().isEmpty())
        assertTrue(session.state.pageMarks(0).isEmpty())
        assertTrue(session.state.writtenPages.isEmpty())
        assertEquals(PdfPhase.Finished, session.state.phase)
    }

    @Test fun `失敗は再開待ちにし成功していた段落の訳を保持する`() {
        val session = PdfSession()
        val (first, second) = session.start(2,
            testPage(TestLine("First paragraph."), TestLine("Second paragraph.", y = 680.0)))
        session.finish(first, "先頭。")
        val failed = session.reduce(PdfEvent.Translated(second.batch, SegmentUpdate.Failed(second.segments.single().id, "失敗")))
        assertTrue(PdfEffect.StopTranslation in failed)
        assertEquals(PdfPhase.Paused, session.state.phase)
        assertEquals(setOf(1), session.state.untranslated[0])
        assertEquals(listOf("Second paragraph."), session.reduce(PdfEvent.Retry).translations().single().segments.map { it.text })
    }

    @Test fun `本文の確定訳をしおりに使い中止後も残し別の文書を開いたら消す`() {
        val session = PdfSession()
        val (title, model) = session.start(2, testPage(TestLine("Power Supply", bold = true), TestLine("PD-100 AC", y = 680.0)))
        session.reduce(PdfEvent.Translated(title.batch, SegmentUpdate.Partial(title.segments.single().id, "途中。")))
        assertNull(session.state.titleTranslation("POWER SUPPLY"))
        session.finish(title, "電源")
        assertEquals("電源", session.state.titleTranslation(" <a1> POWER  SUPPLY </a1> "))
        session.finish(model, "PD-100 AC")
        assertNull(session.state.titleTranslation("PD-100 AC"))
        session.reduce(PdfEvent.Stop)
        assertEquals(mapOf("power supply" to "電源"), session.state.titleTranslations)
        session.reduce(PdfEvent.Open("new.pdf", "new.pdf"))
        assertTrue(session.state.titleTranslations.isEmpty())
    }

    @Test fun `翻訳中に訳す先を変えると同じ表示ページを起点に新しい言語で頼み直す`() {
        val session = PdfSession()
        val pages = arrayOf(testPage(TestLine("First paragraph.")), testPage(TestLine("Last paragraph.")))
        val initial = session.start(1, *pages).single()
        session.reduce(PdfEvent.Page(1))
        val change = session.reduce(PdfEvent.Target(Language.EN))
        assertTrue(PdfEffect.StopTranslation in change)
        val open = change.read<PdfRead.Open>()
        assertEquals("test.pdf", open.path)
        var effects = session.reduce(PdfEvent.Opened(open, 2))
        assertEquals(1, session.state.page)
        for (page in pages) effects = session.reduce(PdfEvent.Extracted(effects.read(), page))
        val request = effects.translations().single()
        assertEquals(Language.EN, request.target)
        assertEquals("Last paragraph.", request.segments.single().text)
        session.finish(initial, "以前の言語の訳。")
        assertTrue(session.state.translations.isEmpty())
    }

    private fun PdfSession.start(slots: Int, vararg pages: PdfPageText): List<PdfEffect.Translate> {
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

    private fun PdfSession.finish(effect: PdfEffect.Translate, text: String): List<PdfEffect> =
        reduce(PdfEvent.Translated(effect.batch, SegmentUpdate.Final(effect.segments.single().id, text)))

    private inline fun <reified Read : PdfRead> List<PdfEffect>.read(): Read =
        filterIsInstance<PdfEffect.Read>().map { it.request }.filterIsInstance<Read>().single()

    private fun List<PdfEffect>.translations() = filterIsInstance<PdfEffect.Translate>()
    private fun List<PdfEffect>.writes() = filterIsInstance<PdfEffect.WritePage>()
}
