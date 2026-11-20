package app.plamotranslate.pipeline.translation

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 翻訳モデルの読み込みと切り替え（[ModelHost]）。偽のローダーで、読み込みの遅れ・失敗・ファイルが無い、を作る。 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelHostTest {
    private class FakeEngine(val model: String) : JobEngine {
        override var parallel = 1
        var closed = false
        override suspend fun run(slots: Int, source: JobSource, maxTokens: Int) = 0
    }

    /** 読み込みは [gate] が開くまで終わらない（開けなければその場で終わる）。 */
    private class FakeLoader : EngineLoader<String, FakeEngine> {
        val loads = mutableListOf<String>()
        val engines = mutableListOf<FakeEngine>()
        var gate: CompletableDeferred<Unit>? = null
        val missingModels = mutableSetOf<String>()
        val failingModels = mutableSetOf<String>()

        override fun missing(model: String) = if (model in missingModels) "/models/$model" else null

        override suspend fun load(model: String, parallel: Int): FakeEngine {
            loads += model
            gate?.await()
            if (model in failingModels) error("読めない")
            return FakeEngine(model).also { it.parallel = parallel; engines += it }
        }

        override fun usesGpu(engine: FakeEngine) = false
        override fun setParallel(engine: FakeEngine, parallel: Int) {
            engine.parallel = parallel
        }

        override suspend fun close(engine: FakeEngine) {
            engine.closed = true
        }
    }

    private val loader = FakeLoader()
    private var selected = "small"
    private var parallel = 1

    private fun TestScope.host() = ModelHost(this, loader, { selected }, { parallel })

    private fun ModelHost<String, FakeEngine>.ready() = state.value as ModelState.Ready<String>

    @Test
    fun `同じモデルを選んでも読み直さず、別のモデルは前のエンジンを閉じてから読む`() = runTest {
        val host = host()
        host.ensureLoaded()
        runCurrent()
        assertEquals("small", host.ready().model)

        host.select("small")
        runCurrent()
        assertEquals(listOf("small"), loader.loads)

        selected = "large"
        host.select("large")
        assertEquals(ModelState.Loading, host.state.value)
        runCurrent()
        assertEquals("large", host.ready().model)
        assertTrue(loader.engines[0].closed)
        assertEquals(2L, host.ready().generation)
    }

    @Test
    fun `読み込み中に選び直したら、読み込みの後に選んだモデルを読む`() = runTest {
        val host = host()
        loader.gate = CompletableDeferred()
        host.ensureLoaded()
        runCurrent()
        selected = "large"
        host.select("large")
        loader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("small", "large"), loader.loads)
        assertEquals("large", host.ready().model)
        assertTrue(loader.engines[0].closed)
    }

    @Test
    fun `読み込み中に読み直しを頼まれたら、読み込みの後に読み直す`() = runTest {
        val host = host()
        loader.gate = CompletableDeferred()
        host.ensureLoaded()
        runCurrent()
        host.reload()
        loader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(listOf("small", "small"), loader.loads)
        assertEquals(2L, host.ready().generation)
    }

    @Test
    fun `同時に訳す数を変えても世代は変わらず、読み込み中に変えた数は読み込みの後に効く`() = runTest {
        val host = host()
        loader.gate = CompletableDeferred()
        host.ensureLoaded()
        runCurrent()
        parallel = 3
        host.setParallel(3)
        loader.gate!!.complete(Unit)
        runCurrent()
        assertEquals(3, host.ready().parallel)
        assertEquals(3, loader.engines[0].parallel)

        host.setParallel(2)
        assertEquals(2, host.ready().parallel)
        assertEquals(1L, host.ready().generation)
    }

    @Test
    fun `ファイルが無い・読めないは状態で返し、エンジンを渡さない`() = runTest {
        val host = host()
        loader.missingModels += "small"
        assertEquals(null, host.awaitEngine())
        assertEquals(ModelState.Missing("/models/small"), host.state.value)

        loader.missingModels.clear()
        loader.failingModels += "small"
        host.ensureLoaded()
        runCurrent()
        assertTrue(host.state.value is ModelState.Failed)

        loader.failingModels.clear()
        host.ensureLoaded()
        val (engine, ready) = host.awaitEngine()!!
        assertEquals("small", engine.model)
        assertEquals(1L, ready.generation)
    }
}
