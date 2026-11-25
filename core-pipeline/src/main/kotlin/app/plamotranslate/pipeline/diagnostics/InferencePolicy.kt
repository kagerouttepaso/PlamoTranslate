package app.plamotranslate.pipeline.diagnostics

enum class InferenceRole(val key: String) { Translation("translation"), Transcription("transcription") }

data class InferenceTarget(val gpuAllowed: Boolean, val requestGpu: Boolean) {
    val useGpu: Boolean get() = gpuAllowed && requestGpu
}

object InferencePolicy {
    fun target(gpuAvailable: Boolean, cpuOnly: Boolean, roleAllowsGpu: Boolean) =
        InferenceTarget(gpuAvailable && !cpuOnly, roleAllowsGpu)
}
