package app.plamotranslate.pipeline.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecognitionWorkerTest {
    private fun request(generation: Long) = RecognitionRequest(generation, RecognitionJob.Transcribe(
        1, 1, RecognitionPriority.PARTIAL, FloatArray(1), 1, "途中", TranscriptLanguage.Unspecified,
    ))

    @Test
    fun `実行中の取消と遅れて購読した空の成功を区別する`() = runBlocking {
        val entered = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val output = RecognitionOutput.Transcribed(Recognition("", null))
        RecognitionWorker(RecognitionLane.TRANSCRIBE) {
            entered.countDown()
            check(resume.await(5, TimeUnit.SECONDS))
            output
        }.use { worker ->
            val first = request(1)
            worker.submit(first)
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            worker.cancel(first.id)
            resume.countDown()
            assertEquals(RecognitionEnd.Cancelled(first.id), withTimeout(5000) { worker.results.first() })
            val next = request(2)
            worker.submit(next)
            worker.cancel(first.id)
            assertEquals(RecognitionEnd.Success(next.id, output), withTimeout(5000) { worker.results.first() })
        }
    }
}
