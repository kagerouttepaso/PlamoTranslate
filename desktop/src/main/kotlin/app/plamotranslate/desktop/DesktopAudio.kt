package app.plamotranslate.desktop

import app.plamotranslate.asr.Qwen3Recognizer
import app.plamotranslate.asr.JvmSenseVoice
import app.plamotranslate.asr.JvmWhisperIdentifier
import app.plamotranslate.asr.SileroVoiceActivity
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.IdentifiedRecognizer
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechPipeline
import app.plamotranslate.pipeline.speech.UtteranceRetention
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Path

internal enum class AudioSource(val label: String) {
    MICROPHONE("マイク"),
    PLAYBACK("PC の再生音"),
}

internal fun interface AudioCapture {
    suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit)
}

internal data class DesktopRecognitionSettings(
    val model: SpeechModel = SpeechModel.DEFAULT,
    val transcribeThreads: Int = 0,
    val identifyThreads: Int = 2,
    val cardLanguageSeconds: Float = SegmentationProfile.CONVERSATION.cardLanguageSeconds,
    val vadThreshold: Float = 0.5f,
    val vadMinSpeechSeconds: Float = 0.25f,
)

internal object WindowsAudio : AudioCapture {
    private val library by lazy { System.loadLibrary("mockingword-audio") }
    private val microphone = Mutex()

    override suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit) {
        val mic = source == AudioSource.MICROPHONE
        check(!mic || microphone.tryLock()) { "マイクは音声翻訳で使用中です。先に停止してください。" }
        try {
            // COM の初期化・読み取り・解放を同じスレッドで行う。このブロック内では中断しない。
            withContext(Dispatchers.IO) {
                library
                val handle = open(!mic)
                try {
                    if (isActive) started()
                    while (isActive) {
                        val samples = read(handle)
                        if (samples != null && isActive) accept(samples)
                    }
                } finally {
                    close(handle)
                }
            }
        } finally {
            if (mic) microphone.unlock()
        }
    }

    private external fun open(loopback: Boolean): Long
    private external fun read(handle: Long): FloatArray?
    private external fun close(handle: Long)
}

internal class DesktopAsr(private val models: Path) {
    private val directory = models.resolve("asr").toFile()
    private val runtime by lazy {
        val sherpa = checkNotNull(System.getProperty("mockingword.sherpa.dir")) { "sherpa-onnx の DLL の場所を指定してください。" }
        // 名前だけで依存 DLL を解決すると、PATH より先に System32 の旧版が選ばれる。
        System.load(File(sherpa, "onnxruntime.dll").absolutePath)
    }

    fun supportsAutomatic(model: SpeechModel): Boolean = model.identifiesLanguage ||
        SharedModelCatalog.whisperTiny.all { DesktopSpeechModels.isInstalled(models, it) }

    fun hasRequiredFiles(model: SpeechModel): Boolean =
        DesktopSpeechModels.requiredFiles(model).all { DesktopSpeechModels.isInstalled(models, it) }

    fun load(settings: DesktopRecognitionSettings): SpeechEngine {
        val qwen = File(directory, Qwen3Recognizer.DIR)
        val vad = models.resolve(SharedModelCatalog.sileroVad.path).toFile()
        val identify = supportsAutomatic(settings.model)
        val missing = DesktopSpeechModels.requiredFiles(settings.model)
            .firstOrNull { !DesktopSpeechModels.isInstalled(models, it) }
        require(missing == null) { "音声認識モデルがありません。設定の「音声認識」からダウンロードしてください。\n${missing?.path}" }

        runtime
        val profile = SegmentationProfile.CONVERSATION.copy(cardLanguageSeconds = settings.cardLanguageSeconds)
        val voice = SileroVoiceActivity.load(vad, profile.phraseSilenceSeconds, settings.vadThreshold, settings.vadMinSpeechSeconds)
        return try {
            val maxThreads = if (settings.model == SpeechModel.SENSE_VOICE) 4 else Qwen3Recognizer.THREADS
            val threads = settings.transcribeThreads.takeIf { it > 0 }
                ?: (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, maxThreads)
            val recognizer = when (settings.model) {
                SpeechModel.QWEN3_ASR -> {
                    val qwenRecognizer = Qwen3Recognizer.load(qwen, threads)
                    try {
                        if (identify) IdentifiedRecognizer(JvmWhisperIdentifier(directory, settings.identifyThreads), qwenRecognizer)
                        else qwenRecognizer
                    } catch (error: Throwable) {
                        qwenRecognizer.close()
                        throw error
                    }
                }
                SpeechModel.SENSE_VOICE -> JvmSenseVoice(directory, threads)
            }
            SpeechPipeline(voice, recognizer, profile, heavy = settings.model.heavy, retainUtterances = UtteranceRetention.KEEP_UTTERANCES)
        } catch (error: Throwable) {
            voice.close()
            throw error
        }
    }
}
