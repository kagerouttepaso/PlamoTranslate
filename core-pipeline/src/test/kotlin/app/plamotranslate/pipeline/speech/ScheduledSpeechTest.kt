package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeWork
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ScheduledSpeechTest {
    @Test fun 設定値を毎回要求して貸出数が変わったときだけ読み直す() = runTest {
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
        val loads = mutableListOf<Int>()
        val closed = mutableListOf<Int>()
        ScheduledSpeech.senseVoice(compute, 4) { threads ->
            loads.add(threads)
            object : SpeechRecognizer {
                override fun recognize(samples: FloatArray) = Recognition(threads.toString(), null)
                override fun close() { closed.add(threads) }
            }
        }.use { recognizer ->
            for (count in listOf(2, 2, 4)) {
                budget = count
                val result = recognizer.transcribe(floatArrayOf(1f), null, RecognitionPriority.FINAL)
                assertEquals(count.toString(), result.text)
            }
        }
        assertEquals(listOf(4, 2, 4), loads)
        assertEquals(loads, closed)
        assertEquals(listOf(ComputeWork.Regular) + List(3) { app.plamotranslate.pipeline.speech.RecognitionPriority.FINAL.work }, observedWork)
    }
}
