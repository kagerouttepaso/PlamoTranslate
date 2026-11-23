package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.Job
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.receiveAsFlow
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

internal data class RecognitionId(val generation: Long, val number: Long)

internal class RecognitionRequest(val generation: Long, val job: RecognitionJob) {
    val id = RecognitionId(generation, job.id)
}

internal sealed interface RecognitionEnd {
    val id: RecognitionId
    data class Success(override val id: RecognitionId, val output: RecognitionOutput) : RecognitionEnd
    data class Cancelled(override val id: RecognitionId) : RecognitionEnd
    data class Failed(override val id: RecognitionId, val cause: Throwable) : RecognitionEnd
}

internal class RecognitionWorker(private val executor: ExecutorService, private val run: suspend (RecognitionJob) -> RecognitionOutput) : AutoCloseable {
    constructor(lane: RecognitionLane, run: suspend (RecognitionJob) -> RecognitionOutput) : this(
        Executors.newSingleThreadExecutor { Thread(it, "PlamoAsr-${lane.name.lowercase()}") }, run,
    )
    private val completed = Channel<RecognitionEnd>(Channel.UNLIMITED)
    val results = completed.receiveAsFlow()
    private class Active(val id: RecognitionId) { val lifetime = Job() }
    private var active: Active? = null

    @Synchronized
    fun submit(request: RecognitionRequest) {
        check(!executor.isShutdown && active == null)
        val operation = Active(request.id)
        active = operation
        executor.execute {
            val end = try {
                RecognitionEnd.Success(request.id, runBlocking(operation.lifetime) { run(request.job) })
            } catch (_: CancellationException) {
                RecognitionEnd.Cancelled(request.id)
            } catch (error: Throwable) {
                RecognitionEnd.Failed(request.id, error)
            }
            synchronized(this) {
                val result = if (operation.lifetime.isCancelled) RecognitionEnd.Cancelled(request.id) else end
                operation.lifetime.complete()
                active = null
                completed.trySend(result).getOrThrow()
            }
        }
    }

    @Synchronized
    fun cancel(id: RecognitionId) {
        active?.takeIf { it.id == id }?.lifetime?.cancel()
    }

    override fun close() {
        synchronized(this) {
            active?.lifetime?.cancel()
            executor.shutdown()
        }
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        completed.close()
    }
}
