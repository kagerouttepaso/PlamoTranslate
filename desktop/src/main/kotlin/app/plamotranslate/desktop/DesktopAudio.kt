package app.plamotranslate.desktop

import app.plamotranslate.asr.Qwen3Recognizer
import app.plamotranslate.asr.JvmSenseVoice
import app.plamotranslate.asr.JvmWhisperIdentifier
import app.plamotranslate.asr.SileroVoiceActivity
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

internal enum class AudioSource(val label: String) {
    MICROPHONE("マイク"),
    PLAYBACK("PC の再生音"),
}

internal fun interface AudioCapture {
    suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit)
}

internal data class DesktopRecognitionSettings(
    val model: SpeechModel = SpeechModel.QWEN3_ASR,
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

internal class DesktopAsr(private val directory: File) {
    private val runtime by lazy {
        val sherpa = checkNotNull(System.getProperty("mockingword.sherpa.dir")) { "sherpa-onnx の DLL の場所を指定してください。" }
        // 名前だけで依存 DLL を解決すると、PATH より先に System32 の旧版が選ばれる。
        System.load(File(sherpa, "onnxruntime.dll").absolutePath)
    }

    fun supportsAutomatic(model: SpeechModel): Boolean = model.identifiesLanguage ||
        JvmWhisperIdentifier.files.all { File(directory, it).isFile && File(directory, it).canRead() }

    fun load(settings: DesktopRecognitionSettings): SpeechEngine {
        val qwen = File(directory, Qwen3Recognizer.DIR)
        val vad = File(directory, "silero_vad.onnx")
        val identify = supportsAutomatic(settings.model)
        val qwenFiles = Qwen3Recognizer.FILES + listOf("tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
        val files = when (settings.model) {
            SpeechModel.QWEN3_ASR -> qwenFiles.map { File(qwen, it) } +
                if (identify) JvmWhisperIdentifier.files.map { File(directory, it) } else emptyList()
            SpeechModel.SENSE_VOICE -> JvmSenseVoice.files.map { File(directory, it) }
        }
        val missing = (listOf(vad) + files).firstOrNull { !it.isFile || !it.canRead() }
        require(missing == null) { "音声認識モデルがありません。設定の音声認識とモデルの配置を確認してください。\n${missing?.path}" }

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
