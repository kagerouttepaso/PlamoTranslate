package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.GpuWork
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.translation.TranslationTurn
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

class LlamaRuntime internal constructor(
    private val initCpu: (String, String) -> Unit,
    private val registerGpu: (String) -> Boolean,
) {
    private val registry = ReentrantReadWriteLock(true)
    private var gpuAllowed: Boolean? = null
    private var gpuRegistered: Boolean? = null
    private val preparation = Mutex()

    internal fun initialize(directory: String, variant: String, target: InferenceTarget): Boolean {
        registry.read {
            if (gpuAllowed != null && (!target.useGpu || gpuAllowed == false || gpuRegistered != null)) {
                return target.useGpu && gpuRegistered == true
            }
        }
        return registry.write {
            if (gpuAllowed == null) {
                initCpu(directory, variant)
                gpuAllowed = target.gpuAllowed
            }
            if (!target.useGpu || gpuAllowed != true) false
            else gpuRegistered ?: registerGpu(directory).also { gpuRegistered = it }
        }
    }

    internal fun <T> native(action: () -> T): T = registry.read(action)

    suspend fun <T> prepare(gpuCandidate: Boolean, action: suspend () -> T): T =
        if (gpuCandidate) preparation.withLock { action() } else action()

    private class Ticket(val work: GpuWork, val ready: CompletableDeferred<Unit> = CompletableDeferred())
    private val waiting = mutableListOf<Ticket>()
    private var owner: Ticket? = null

    private fun grantNext(): Ticket? {
        owner = InferencePolicy.next(waiting) { it.work }?.also { waiting.remove(it) }
        return owner
    }

    internal suspend fun <T> onDevice(gpu: Boolean, work: GpuWork = GpuWork.Regular, action: suspend (() -> Boolean) -> T): T {
        if (!gpu) return action { false }
        val ticket = Ticket(work)
        synchronized(waiting) {
            waiting.add(ticket)
            if (owner == null) grantNext() else null
        }?.ready?.complete(Unit)
        try {
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            return action { synchronized(waiting) { waiting.isNotEmpty() } }
        } finally {
            synchronized(waiting) {
                waiting.remove(ticket)
                if (owner === ticket) grantNext() else null
            }?.ready?.complete(Unit)
        }
    }

    internal suspend fun translationBatches(gpu: Boolean, slots: Int, batch: suspend (TranslationTurn) -> Int): Int {
        var tokens = 0
        do {
            val turn = onDevice(gpu) { hasWaiters ->
                TranslationTurn(slots, hasWaiters).also { tokens += batch(it) }
            }
        } while (turn.yielded)
        return tokens
    }

    companion object {
        val process = LlamaRuntime({ directory, variant -> LlamaNative.nativeInitCpu(directory, variant) },
            { directory -> LlamaNative.nativeRegisterGpu(directory) })
    }
}
