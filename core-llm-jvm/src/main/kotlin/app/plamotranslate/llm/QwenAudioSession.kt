package app.plamotranslate.llm

import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeWork

/** 認識器が直列に使うネイティブモデル。要求数は固定し、貸出数に合わせて読み直す。 */
internal class QwenAudioSession(
    private val compute: ComputeResources,
    private val gpu: Boolean,
    private val requested: Int,
    private var loadedThreads: Int,
    private var handle: Long,
    private val load: (Int) -> Long,
    private val free: (Long) -> Unit,
) : AutoCloseable {
    val cpuThreads get() = if (gpu) 0 else requested

    suspend fun <T> use(work: ComputeWork, action: suspend (Long) -> T): T =
        compute.borrow(gpu, requested, work) { count, _ ->
            if (handle == 0L || loadedThreads != count) {
                close()
                handle = load(count)
                loadedThreads = count
            }
            action(handle)
        }

    override fun close() {
        if (handle != 0L) {
            free(handle)
            handle = 0
        }
    }
}
