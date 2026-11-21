package app.plamotranslate.desktop

import app.plamotranslate.asr.Qwen3Recognizer
import app.plamotranslate.asr.SileroVoiceActivity
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechPipeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

internal enum class AudioSource(val label: String) {
    MICROPHONE("マイク"),
    PLAYBACK("PC の再生音"),
}

internal fun interface AudioCapture {
    suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit)
}

internal object WindowsAudio : AudioCapture {
    private val library by lazy { System.loadLibrary("mockingword-audio") }

    override suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit) {
        // COM の初期化・読み取り・解放を同じスレッドで行う。このブロック内では中断しない。
        withContext(Dispatchers.IO) {
            library
            val handle = open(source == AudioSource.PLAYBACK)
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

    fun load(): SpeechEngine {
        val qwen = File(directory, Qwen3Recognizer.DIR)
        val vad = File(directory, "silero_vad.onnx")
        val qwenFiles = Qwen3Recognizer.FILES + listOf("tokenizer/merges.txt", "tokenizer/tokenizer_config.json")
        val missing = (listOf(vad) + qwenFiles.map { File(qwen, it) }).firstOrNull { !it.isFile || !it.canRead() }
        require(missing == null) { "音声認識モデルがありません。配置を確認してください。\n${missing?.path}" }

        runtime
        val profile = SegmentationProfile.CONVERSATION
        val voice = SileroVoiceActivity.load(vad, profile.phraseSilenceSeconds)
        return try {
            val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceIn(1, Qwen3Recognizer.THREADS)
            val recognizer = Qwen3Recognizer.load(qwen, threads)
            SpeechPipeline(voice, recognizer, profile, heavy = SpeechModel.QWEN3_ASR.heavy)
        } catch (error: Throwable) {
            voice.close()
            throw error
        }
    }
}
