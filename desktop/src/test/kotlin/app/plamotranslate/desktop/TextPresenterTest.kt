package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.EngineLoader
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class TextPresenterTest {
    @Test
    fun `翻訳中は途中訳を表示し確定した訳だけをコピーする`() = runTest {
        var copied = ""
        val model = host(backgroundScope)
        val presenter = TextPresenter(backgroundScope, model.state, DesktopTranslations(model)) { copied = it }
        model.ensureLoaded()
        runCurrent()
        assertFalse(presenter.state.value.canTranslate)

        presenter.edit("今日は天気がいいですね。")
        presenter.selectSource(Language.JA)
        assertTrue(presenter.state.value.canTranslate)
        presenter.translate()
        runCurrent()
        assertEquals("It is ", presenter.state.value.output)
        presenter.copyOutput()
        assertEquals("", copied)

        advanceTimeBy(500)
        runCurrent()
        assertFalse(presenter.state.value.translating)
        presenter.copyOutput()
        assertEquals("It is sunny.", copied)
    }

    @Test
    fun `取り消すと古い翻訳の結果は表示されない`() = runTest {
        val model = host(backgroundScope)
        val presenter = TextPresenter(backgroundScope, model.state, DesktopTranslations(model)) {}
        model.ensureLoaded()
        runCurrent()
        presenter.edit("今日は天気がいいですね。")
        presenter.translate()
        runCurrent()
        assertTrue(presenter.state.value.translating)

        presenter.cancel()
        advanceTimeBy(500)
        runCurrent()
        assertEquals("", presenter.state.value.output)
        assertTrue(presenter.state.value.canTranslate)
    }

    @Test
    fun `モデルが無ければ原文を入力しても翻訳を開始しない`() = runTest {
        val model = host(backgroundScope, missing = true)
        val presenter = TextPresenter(backgroundScope, model.state, DesktopTranslations(model)) {}
        model.ensureLoaded()
        runCurrent()
        presenter.edit("今日は天気がいいですね。")
        presenter.translate()

        assertEquals(ModelState.Missing("model.gguf"), presenter.state.value.model)
        assertFalse(presenter.state.value.canTranslate)
        assertFalse(presenter.state.value.translating)
    }

    private fun host(scope: CoroutineScope, missing: Boolean = false): ModelHost<Path, FakeEngine> =
        ModelHost(scope, object : EngineLoader<Path, FakeEngine> {
            override fun missing(model: Path) = model.toString().takeIf { missing }
            override suspend fun load(model: Path, parallel: Int) = FakeEngine(parallel)
            override fun usesGpu(engine: FakeEngine) = false
            override fun setParallel(engine: FakeEngine, parallel: Int) { engine.parallel = parallel }
            override suspend fun close(engine: FakeEngine) = Unit
        }, { Path.of("model.gguf") }, { 1 })

    private class FakeEngine(override var parallel: Int) : JobEngine {
        override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int {
            val (id, _) = checkNotNull(source.next())
            source.onText(id, "It is ")
            delay(500)
            source.onText(id, "sunny.")
            source.onFinished(id)
            return 2
        }
    }
}
