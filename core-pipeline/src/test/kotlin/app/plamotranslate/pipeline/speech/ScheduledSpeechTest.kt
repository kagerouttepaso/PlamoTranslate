package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.compute.ThreadTrialBoard
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeWork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScheduledSpeechTest {
    @Test fun 試走の候補と実行用のモデルは前のモデルを閉じてから読む() = runTest {
        val models = FakeModels()
        val compute = object : ComputeResources {
            override suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean, capacity: Int, reserved: Int): Int =
                benchmark.select(ThreadTrialBoard(), "test", listOf(1, 2), { true }) { trials, count ->
                    trials.measure(count, { benchmark.sample(count) }) {}
                }.preferred()

            override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
                action: suspend (Int, () -> Boolean) -> T): T = action(1) { false }
        }
        ScheduledSpeech.senseVoice(compute, 0, create = models::create).use { recognizer ->
            assertEquals(listOf(1, 2, 1), models.loads)
            assertEquals("1", recognizer.transcribe(floatArrayOf(1f), null, RecognitionPriority.FINAL).text)
        }
        assertEquals(models.loads, models.closed)
    }

    @Test fun 貸出数が変わると閉じてから読み直し読込失敗後も再試行できる() = runTest {
        var budget = 4
        val observedWork = mutableListOf<ComputeWork>()
        val compute = object : ComputeResources {
            override suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean, capacity: Int, reserved: Int) = requested
            override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
                action: suspend (Int, () -> Boolean) -> T): T {
                assertFalse(gpu)
                assertEquals(4, threads)
                observedWork.add(work)
                return action(minOf(threads, budget)) { false }
            }
        }
        val models = FakeModels()
        ScheduledSpeech.senseVoice(compute, 4, create = models::create).use { recognizer ->
            suspend fun transcribe(count: Int): String {
                budget = count
                return recognizer.transcribe(floatArrayOf(1f), null, RecognitionPriority.FINAL).text
            }
            assertEquals("2", transcribe(2))
            assertEquals("2", transcribe(2))
            models.fail = true
            assertTrue(runCatching { transcribe(4) }.exceptionOrNull() is IllegalStateException)
            assertEquals(models.loads, models.closed)
            models.fail = false
            // 失敗する前と同じ貸出数に戻っても、閉じたモデルは使わない。
            assertEquals("2", transcribe(2))
            assertEquals("4", transcribe(4))
        }
        assertEquals(listOf(4, 2, 2, 4), models.loads)
        assertEquals(models.loads, models.closed)
        assertEquals(listOf(ComputeWork.Regular) + List(5) { RecognitionPriority.FINAL.work }, observedWork)
    }

    @Test fun 言語の識別は書き起こしの分を残して数を選ぶ() = runTest {
        var reservedSeen = -1
        val compute = object : ComputeResources {
            override suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean, capacity: Int, reserved: Int): Int {
                reservedSeen = reserved
                return 2
            }
            override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
                action: suspend (Int, () -> Boolean) -> T): T = action(threads) { false }
        }
        ScheduledSpeech.identifier(compute, 0, null, transcriberThreads = 6) {
            object : LanguageIdentifier {
                override fun scores(samples: FloatArray, candidates: Set<String>?) = emptyMap<String, Float>()
                override fun close() {}
            }
        }.close()
        assertEquals(6, reservedSeen)
    }

    private class FakeModels {
        val loads = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        var fail = false

        fun create(threads: Int): SpeechRecognizer {
            assertEquals(0, loads.size - closed.size)
            check(!fail) { "読み込み失敗" }
            loads.add(threads)
            return object : SpeechRecognizer {
                private var open = true
                override fun recognize(samples: FloatArray): Recognition {
                    assertTrue(open)
                    return Recognition(threads.toString(), null)
                }

                override fun close() {
                    assertTrue(open)
                    open = false
                    closed.add(threads)
                }
            }
        }
    }
}
