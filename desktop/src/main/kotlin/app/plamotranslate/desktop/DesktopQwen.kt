package app.plamotranslate.desktop

import app.plamotranslate.llm.LlamaRuntime
import app.plamotranslate.llm.QwenAudioRecognizer
import app.plamotranslate.llm.QwenTrialAudio
import app.plamotranslate.pipeline.diagnostics.InferencePolicy
import app.plamotranslate.pipeline.diagnostics.InferenceRole
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.TranscriptionPreference
import app.plamotranslate.pipeline.speech.TranscriptionTuner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID

internal class DesktopQwen(private val settings: DesktopSettings, private val nativeDir: String, private val permitGpu: Boolean) {
    private val preparing = Mutex()
    private val role = InferenceRole.Transcription
    private val status = MutableStateFlow("未準備")
    val state = status.asStateFlow()
    private external fun nativeDisplayDrivers(): String
    private val unknownEnvironment = UUID.randomUUID().toString()
    private val driver by lazy {
        System.loadLibrary("plamo-llm")
        nativeDisplayDrivers().ifBlank { unknownEnvironment }
    }
    private val runtime by lazy {
        val digest = MessageDigest.getInstance("SHA-256")
        File(nativeDir).listFiles().orEmpty().filter { it.extension == "dll" &&
            (it.name.startsWith("ggml") || it.nameWithoutExtension in setOf("plamo-llm", "mtmd", "llama"))
        }.sortedBy { it.name }.forEach { file ->
            DigestInputStream(file.inputStream(), digest).use { it.transferTo(java.io.OutputStream.nullOutputStream()) }
        }
        "${System.getProperty("mockingword.version", "開発版")}:${HexFormat.of().formatHex(digest.digest())}"
    }

    suspend fun load(threads: Int): QwenAudioRecognizer = preparing.withLock {
        status.value = "書き起こしを準備中"
        val processors = Runtime.getRuntime().availableProcessors()
        val weights = SharedModelCatalog.qwenWeights
        val projector = SharedModelCatalog.qwenProjector
        val target = InferencePolicy.target(permitGpu, settings.cpuOnly, settings.gpuFallback(role) == null)
        val preference = settings.transcriptionPreference
        val gpu = target.useGpu && File(nativeDir, "ggml-vulkan.dll").isFile && preference != TranscriptionPreference.CPU
        try {
            LlamaRuntime.process.prepare(gpu) {
                val environment = TranscriptionTuner.Environment(weights.sha256, projector.sha256, runtime,
                    listOf("os.name", "os.version", "os.arch").joinToString { System.getProperty(it) },
                    "${System.getenv("PROCESSOR_IDENTIFIER")?.takeIf { it.isNotBlank() } ?: unknownEnvironment}:$processors", if (gpu) driver else "CPU", QwenTrialAudio.version)
                val (engine, choice) = QwenAudioRecognizer.prepare(settings.modelDirectory.toFile(), nativeDir, "",
                    target.copy(requestGpu = gpu), processors, threads, preference, environment, settings,
                    { settings.beginGpuLoad(role) }, { settings.endGpuLoad(role, it) })
                val candidate = choice.candidate
                val reason = settings.gpuFallback(role) ?: if (!gpu && preference == TranscriptionPreference.GPU) "GPU を使用できません" else choice.reason
                status.value = "${if (candidate.gpu) "Vulkan" else "CPU"}（${candidate.batch}/${candidate.generation} スレッド）：$reason"
                engine
            }
        } catch (error: Throwable) {
            status.value = if (error is CancellationException) "未準備" else "準備失敗：${error.message}"
            throw error
        }
    }
}
