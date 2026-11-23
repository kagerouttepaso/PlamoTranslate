package app.plamotranslate.pipeline.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.CompletableDeferred
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
    fun `開始前に取り消した要求は走らず世代をまたいで各要求が一度だけ終わる`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val start = CountDownLatch(1)
        executor.execute { start.await(5, TimeUnit.SECONDS) }
        val output = RecognitionOutput.Transcribed(Recognition("確定", "ja"))
        var executions = 0
        val worker = RecognitionWorker(executor) { executions++; output }
        try {
            val old = request(1)
            worker.submit(old)
            worker.cancel(old.id)
            start.countDown()
            assertEquals(RecognitionEnd.Cancelled(old.id), withTimeout(5000) { worker.results.first() })
            val next = request(2)
            worker.submit(next)
            worker.cancel(old.id)
            assertEquals(RecognitionEnd.Success(next.id, output), withTimeout(5000) { worker.results.first() })
        } finally { start.countDown(); worker.close() }
        assertEquals(1, executions)
        assertTrue(worker.results.toList().isEmpty())
    }

    @Test(timeout = 5000)
    fun `終了の確定後に取り消しても未購読の成功は消えない`() = runBlocking {
        val executor = Executors.newSingleThreadExecutor()
        val output = RecognitionOutput.Transcribed(Recognition("", null))
        val worker = RecognitionWorker(executor) { output }
        val old = request(1)
        try {
            worker.submit(old)
            executor.submit {}.get(3, TimeUnit.SECONDS)
            worker.cancel(old.id)
        } finally { worker.close() }
        assertEquals(listOf(RecognitionEnd.Success(old.id, output)), worker.results.toList())
    }

    @Test(timeout = 5000)
    fun `解放は実行を取り消し後始末後に終了を一件だけ配信する`() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cleaned = CompletableDeferred<Unit>()
        val worker = RecognitionWorker(RecognitionLane.TRANSCRIBE) {
            entered.complete(Unit)
            try { awaitCancellation() } finally { cleaned.complete(Unit) }
        }
        val old = request(1)
        worker.submit(old)
        entered.await()
        worker.close()
        assertTrue(cleaned.isCompleted)
        assertEquals(listOf(RecognitionEnd.Cancelled(old.id)), worker.results.toList())
        assertTrue(runCatching { worker.submit(request(2)) }.isFailure)
    }

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
