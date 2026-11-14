package app.plamotranslate.asr

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.IdentifiedRecognizer
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import app.plamotranslate.pipeline.speech.SpeechSegmenter
import java.io.File

/**
 * 音声認識の組み立て（docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md「直した後の構造」）。
 * 選んだモデル（[SpeechModel]）ごとに、区切り（Silero VAD）・言語の識別・文字起こしのブロックをつなぐ。
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
    fun load(model: SpeechModel, asrDir: File, profile: SegmentationProfile = SegmentationProfile.CONVERSATION): SpeechEngine {
        val missing = missingFiles(model, asrDir)
        require(missing.isEmpty()) { "音声認識のモデルがありません: ${missing.joinToString()}" }
        val recognizer: SpeechRecognizer = when (model) {
            SpeechModel.SENSE_VOICE -> SenseVoiceRecognizer.load(asrDir)
            SpeechModel.QWEN3_ASR -> IdentifiedRecognizer(
                WhisperLanguageIdentifier.load(File(asrDir, WhisperLanguageIdentifier.DIR)),
                Qwen3Recognizer.load(File(asrDir, Qwen3Recognizer.DIR)),
            )
        }
        return SpeechSegmenter(
            SileroVoiceActivity.load(File(asrDir, VAD), profile.phraseSilenceSeconds),
            recognizer,
            profile,
            byPhrase = model.byPhrase,
            // 区切りの経過は話した言葉を含むので、デバッグ版だけに出る（Logs.text）
            trace = { message -> Logs.text { "会話の認識: ${message()}" } },
        )
    }

    private const val VAD = "silero_vad.onnx"
}
