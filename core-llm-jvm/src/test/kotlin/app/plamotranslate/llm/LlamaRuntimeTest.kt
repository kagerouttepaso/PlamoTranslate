package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.ParallelTuner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LlamaRuntimeTest {
    @Test fun `起動順に依存せずCPUを一度登録しGPUは初めて必要になった時だけ追加する`() {
        for (requests in listOf(listOf(false, true, true), listOf(true, false, true))) {
            val calls = mutableListOf<String>()
            val runtime = LlamaRuntime({ _, variant -> calls += variant }, { calls += "GPU"; true })
            var neededGpu = false
            for (request in requests) {
                neededGpu = neededGpu || request
                assertEquals(request, runtime.initialize("dir", "CPU", InferenceTarget(true, request)))
                assertEquals(if (neededGpu) listOf("CPU", "GPU") else listOf("CPU"), calls)
            }
            assertEquals(listOf("CPU", "GPU"), calls)
        }
        val cpuOnly = LlamaRuntime({ _, _ -> }, { error("CPU専用ではGPUを登録しない") })
        assertFalse(cpuOnly.initialize("dir", "cpu", InferenceTarget(false, true)))
        assertFalse(cpuOnly.initialize("dir", "cpu", InferenceTarget(true, true)))
    }

    @Test(timeout = 10000) fun `初回推論の間は次の準備マーカーもチューナーの計測も始めない`() = runBlocking {
        val runtime = LlamaRuntime({ _, _ -> }, { true })
        val scheduler = app.plamotranslate.pipeline.compute.ComputeScheduler()
        val cpu = app.plamotranslate.pipeline.compute.CpuTopology(mapOf(0 to null))
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val preparing = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.prepare(true) {
                order += "翻訳準備"
                scheduler.borrow(cpu, true, 1) { _, _ -> release.await(); order += "初回推論完了" }
            }
        }
        val other = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.prepare(true) { order += "別役割の準備" }
        }
        runtime.prepare(false) { order += "CPU準備" }
        val engine = object : JobEngine {
            override val parallel = 1
            override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int = scheduler.borrow(cpu, true, 1) { _, waiting ->
                val turn = app.plamotranslate.pipeline.translation.TranslationTurn(slots, waiting)
                order += "計測"
                var count = 0
                while (true) {
                    val (id, _) = turn.next(source::next) ?: break
                    source.onFinished(id)
                    count++
                }
                count
            }
        }
        val tuning = launch(start = CoroutineStart.UNDISPATCHED) {
            ParallelTranslationBenchmark.measure(engine, ParallelTuner.next(emptyMap())!!)
        }
        assertEquals(listOf("翻訳準備", "CPU準備"), order)
        release.complete(Unit)
        listOf(preparing, other, tuning).forEach { it.join() }
        assertEquals(listOf("翻訳準備", "CPU準備", "初回推論完了"), order.take(3))
        assertEquals(2, order.count { it == "計測" })
        assertTrue("別役割の準備" in order)
    }
}
