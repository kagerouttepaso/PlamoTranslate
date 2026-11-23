package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.GpuWork
import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.ParallelTuner
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

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

    @Test(timeout = 10000) fun `翻訳が続いても確定と先着順の待機者へ譲り取り消した要求を実行しない`() = runBlocking {
        val runtime = LlamaRuntime({ _, _ -> }, { true })
        val first = CompletableDeferred<Unit>()
        val resume = CountDownLatch(1)
        val order = mutableListOf<String>()
        val occupied = AtomicInteger()
        val translation = async(Dispatchers.IO) {
            var remaining = 4
            runtime.translationBatches(true, 2) { turn ->
                assertEquals(1, occupied.incrementAndGet())
                var count = 0
                while (remaining > 0) {
                    val number = turn.next { 5 - remaining-- } ?: break
                    order += "文$number"
                    if (first.complete(Unit)) assertTrue(resume.await(5, TimeUnit.SECONDS))
                    count++
                }
                occupied.decrementAndGet()
                count
            }
        }
        first.await()
        fun enqueue(label: String, work: GpuWork = GpuWork.Regular) = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.onDevice(true, work) {
                assertEquals(1, occupied.incrementAndGet())
                order += label
                occupied.decrementAndGet()
            }
        }
        val firstWaiter = enqueue("初回準備")
        val cancelled = enqueue("取消", GpuWork.FinalTranscript)
        val secondWaiter = enqueue("計測")
        val final = enqueue("確定", GpuWork.FinalTranscript)
        cancelled.cancelAndJoin()
        resume.countDown()
        assertEquals(4, translation.await())
        listOf(firstWaiter, secondWaiter, final).forEach { it.join() }
        assertEquals(listOf("文1", "文2", "確定", "初回準備", "計測", "文3", "文4"), order)
    }

    @Test(timeout = 10000) fun `別モデルのCPU同士とCPUとGPUは同時にネイティブ実行できる`() = runBlocking {
        val runtime = LlamaRuntime({ _, _ -> }, { true })
        val entered = CountDownLatch(3)
        listOf(false, false, true).map { gpu ->
            async(Dispatchers.IO) {
                runtime.onDevice(gpu) { runtime.native {
                    entered.countDown()
                    assertTrue(entered.await(5, TimeUnit.SECONDS))
                } }
            }
        }.forEach { it.await() }
    }

    @Test(timeout = 10000) fun `初回推論の間は次の準備マーカーもチューナーの計測も始めない`() = runBlocking {
        val runtime = LlamaRuntime({ _, _ -> }, { true })
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val preparing = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.prepare(true) {
                order += "翻訳準備"
                runtime.onDevice(true) { release.await(); order += "初回推論完了" }
            }
        }
        val other = launch(start = CoroutineStart.UNDISPATCHED) {
            runtime.prepare(true) { order += "別役割の準備" }
        }
        val engine = object : JobEngine {
            override val parallel = 1
            override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int = runtime.translationBatches(true, slots) { turn ->
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
        assertEquals(listOf("翻訳準備"), order)
        release.complete(Unit)
        listOf(preparing, other, tuning).forEach { it.join() }
        assertEquals(listOf("翻訳準備", "初回推論完了"), order.take(2))
        assertEquals(2, order.count { it == "計測" })
        assertTrue("別役割の準備" in order)
    }
}
