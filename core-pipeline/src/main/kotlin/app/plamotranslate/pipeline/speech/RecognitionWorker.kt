package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
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

internal class RecognitionWorker(lane: RecognitionLane, private val run: (RecognitionJob) -> RecognitionOutput) : AutoCloseable {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "PlamoAsr-${lane.name.lowercase()}") }
    private val completed = Channel<RecognitionEnd>(Channel.UNLIMITED)
    val results = completed.receiveAsFlow()
    private class Active(val id: RecognitionId, var cancelled: Boolean = false)
    private var active: Active? = null

    @Synchronized
    fun submit(request: RecognitionRequest) {
        check(!executor.isShutdown && active == null)
        val operation = Active(request.id)
        active = operation
        executor.execute {
            val end = try {
                if (synchronized(this) { !operation.cancelled }) RecognitionEnd.Success(request.id, run(request.job))
                else RecognitionEnd.Cancelled(request.id)
            } catch (error: Throwable) {
                RecognitionEnd.Failed(request.id, error)
            }
            synchronized(this) {
                val result = if (!operation.cancelled) end else RecognitionEnd.Cancelled(request.id)
                active = null
                completed.trySend(result).getOrThrow()
            }
        }
    }

    @Synchronized
    fun cancel(id: RecognitionId) {
        active?.takeIf { it.id == id }?.cancelled = true
    }

    override fun close() {
        synchronized(this) {
            active?.cancelled = true
            executor.shutdown()
        }
        executor.awaitTermination(Long.MAX_VALUE, TimeUnit.NANOSECONDS)
        completed.close()
    }
}
