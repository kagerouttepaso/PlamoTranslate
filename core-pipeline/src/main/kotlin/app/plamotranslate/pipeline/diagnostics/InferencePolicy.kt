package app.plamotranslate.pipeline.diagnostics

enum class InferenceRole(val key: String) { Translation("translation"), Transcription("transcription") }

enum class GpuWork { Regular, FinalTranscript }

data class InferenceTarget(val gpuAllowed: Boolean, val requestGpu: Boolean) {
    val useGpu: Boolean get() = gpuAllowed && requestGpu
}

object InferencePolicy {
    fun target(gpuAvailable: Boolean, cpuOnly: Boolean, roleAllowsGpu: Boolean) =
        InferenceTarget(gpuAvailable && !cpuOnly, roleAllowsGpu)

    fun <T> next(waiting: List<T>, work: (T) -> GpuWork): T? =
        waiting.firstOrNull { work(it) == GpuWork.FinalTranscript } ?: waiting.firstOrNull()
}
