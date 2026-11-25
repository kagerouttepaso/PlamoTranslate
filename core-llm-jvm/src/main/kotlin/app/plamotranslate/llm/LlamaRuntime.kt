package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
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

    companion object {
        val process = LlamaRuntime({ directory, variant -> LlamaNative.nativeInitCpu(directory, variant) },
            { directory -> LlamaNative.nativeRegisterGpu(directory) })
    }
}
