package app.plamotranslate.asr

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.IdentifiedRecognizer
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechPipeline
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import java.io.File

/**
 * 音声認識の実装の値（スレッド数・VAD）。0 は各モデルの既定（[SpeechEngines.load]）。
 * CPU の割り当ては、音声認識・言語の識別・翻訳（llama.cpp）の合計が端末のコアに収まるように決める
 * （docs/設計/会話翻訳.md「認識の段と CPU の割り当て」）。設定の詳細設定で変えて測れる。
 */
data class AsrTuning(
    /** 書き起こしのスレッド数。0 ならモデルの既定（SenseVoice 4、Qwen3-ASR 6）。 */
    val transcribeThreads: Int = 0,
    /** 言語の識別（Whisper tiny）のスレッド数。 */
    val identifyThreads: Int = DEFAULT_IDENTIFY_THREADS,
    val vadThreshold: Float = SileroVoiceActivity.DEFAULT_THRESHOLD,
    val vadMinSpeechSeconds: Float = SileroVoiceActivity.DEFAULT_MIN_SPEECH_SECONDS,
) {
    companion object {
        const val DEFAULT_IDENTIFY_THREADS = 2
    }
}

/**
 * 音声認識の組み立て（docs/設計/会話翻訳.md「エンジンと出来事」）。
 * 選んだモデル（[SpeechModel]）ごとに、区切り（Silero VAD）・言語の識別・文字起こしのブロックをつなぎ、中核の流れ（[SpeechPipeline]）に渡す。
 * 言語を自分で判定するモデル（SenseVoice）はそのまま、判定しないモデル（Qwen3-ASR）は Whisper tiny の識別を前に置く。
 *
 * [asrDir] の中の置き場：`silero_vad.onnx`（共通）、`sense-voice/`、`qwen3-asr/`、`whisper-tiny/`。
 */
object SpeechEngines {
    /** モデルが揃っていなければ、足りないファイルのパスを返す。 */
    fun missingFiles(model: SpeechModel, asrDir: File): List<String> =
        files(model).map { File(asrDir, it) }.filterNot { it.canRead() }.map { it.path }

    fun files(model: SpeechModel): List<String> = listOf(VAD) + when (model) {
        SpeechModel.SENSE_VOICE -> SenseVoiceAsr.MODEL_FILES
        SpeechModel.QWEN3_ASR ->
            Qwen3Recognizer.FILES.map { "${Qwen3Recognizer.DIR}/$it" } +
                WhisperLanguageIdentifier.FILES.map { "${WhisperLanguageIdentifier.DIR}/$it" }
    }

    /** モデルを読み込む。数秒かかるので画面を開いたときに1回だけ呼ぶ。[profile] は用途ごとの区切り方。 */
    fun load(
        model: SpeechModel,
        asrDir: File,
        profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
        tuning: AsrTuning = AsrTuning(),
        /** 書き起こし直しのために保持する確定した発話の数（会話翻訳だけ。0 なら保持しない）。 */
        retainUtterances: Int = 0,
    ): SpeechEngine {
        val missing = missingFiles(model, asrDir)
        require(missing.isEmpty()) { "音声認識のモデルがありません: ${missing.joinToString()}" }
        val recognizer: SpeechRecognizer = when (model) {
            SpeechModel.SENSE_VOICE -> SenseVoiceRecognizer.load(asrDir, tuning.transcribeThreads.takeIf { it > 0 } ?: SenseVoiceRecognizer.THREADS)
            SpeechModel.QWEN3_ASR -> IdentifiedRecognizer(
                WhisperLanguageIdentifier.load(File(asrDir, WhisperLanguageIdentifier.DIR), tuning.identifyThreads),
                Qwen3Recognizer.load(File(asrDir, Qwen3Recognizer.DIR), tuning.transcribeThreads.takeIf { it > 0 } ?: Qwen3Recognizer.THREADS),
            )
        }
        Logs.i("音声認識の組み立て: $model 書き起こし${tuning.transcribeThreads}（0は既定） 識別${tuning.identifyThreads} $profile VAD ${tuning.vadThreshold}/${tuning.vadMinSpeechSeconds}秒")
        return SpeechPipeline(
            SileroVoiceActivity.load(File(asrDir, VAD), profile.phraseSilenceSeconds, tuning.vadThreshold, tuning.vadMinSpeechSeconds),
            recognizer,
            profile,
            heavy = model.heavy,
            // セグメンターの経過は話した言葉を含むので、詳しい記録を残すとき（デバッグ版の既定、正式版は詳細設定で選ぶ）だけ出る（Logs.text）
            trace = { message -> Logs.text { "会話の認識: ${message()}" } },
            // ジョブごとの待ち時間と処理時間は、話した言葉を含まないので正式版でも書く（遅さの切り分けに使う）
            timing = { message -> Logs.i("会話の認識: $message") },
            retainUtterances = retainUtterances,
        )
    }

    private const val VAD = "silero_vad.onnx"
}
