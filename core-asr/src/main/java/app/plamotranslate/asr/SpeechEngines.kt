package app.plamotranslate.asr

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.LanguageIdentifier
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechPipeline
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.speech.ScheduledSpeech
import java.io.File

/**
 * 音声認識の実装の値（スレッド数・VAD）。0 は各モデルの既定（[SpeechEngines.load]）。
 * CPU の割り当ては、音声認識・言語の識別・翻訳（llama.cpp）の合計が端末のコアに収まるように決める
 * （docs/設計/会話翻訳.md「認識の段と CPU の割り当て」）。設定の詳細設定で変えて測れる。
 */
data class AsrTuning(
    /** 書き起こしのスレッド数。0 ならモデルの既定（試走で選ぶ）。 */
    val transcribeThreads: Int = 0,
    /** 言語の識別（Whisper tiny）のスレッド数。 */
    val identifyThreads: Int = DEFAULT_IDENTIFY_THREADS,
    val vadThreshold: Float = SileroVoiceActivity.DEFAULT_THRESHOLD,
    val vadMinSpeechSeconds: Float = SileroVoiceActivity.DEFAULT_MIN_SPEECH_SECONDS,
) {
    companion object {
        const val DEFAULT_IDENTIFY_THREADS = 0
    }
}

/**
 * 音声認識の組み立て（docs/設計/会話翻訳.md「エンジンと出来事」）。
 * 選んだモデル（[SpeechModel]）ごとに、区切り（Silero VAD）・言語の識別・文字起こしのブロックをつなぎ、中核の流れ（[SpeechPipeline]）に渡す。
 * Qwen3-ASR の確定文はモデルの言語判定を使い、話し中の言語識別には Whisper tiny を使う。
 *
 * [asrDir] の中の置き場：`silero_vad.onnx`（共通）、`sense-voice/`、`qwen3-asr-gguf/`、`whisper-tiny/`。
 */
object SpeechEngines {
    /** モデルを読み込む。数秒かかるので画面を開いたときに1回だけ呼ぶ。[profile] は用途ごとの区切り方。 */
    suspend fun load(
        model: SpeechModel,
        asrDir: File,
        compute: ComputeResources,
        profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
        tuning: AsrTuning = AsrTuning(),
        /** 書き起こし直しのために保持する確定した発話の数（会話翻訳だけ。0 なら保持しない）。 */
        retainUtterances: Int = 0,
        loadQwen: suspend () -> SpeechRecognizer,
    ): SpeechEngine {
        val recognizer: SpeechRecognizer = when (model) {
            SpeechModel.SENSE_VOICE -> ScheduledSpeech.senseVoice(compute, tuning.transcribeThreads, asrDir.resolve("thread-profiles")) { SenseVoiceRecognizer.load(asrDir, it) }
            SpeechModel.QWEN3_ASR -> loadQwen()
        }
        var identifier: LanguageIdentifier? = null
        var vad: SileroVoiceActivity? = null
        try {
            identifier = if (model == SpeechModel.QWEN3_ASR) ScheduledSpeech.identifier(compute, tuning.identifyThreads, asrDir.resolve("thread-profiles"), recognizer.cpuThreads) { WhisperLanguageIdentifier.load(File(asrDir, WhisperLanguageIdentifier.DIR), it) } else null
            Logs.i("音声認識の組み立て: $model 書き起こし${tuning.transcribeThreads}（0は既定） 識別${tuning.identifyThreads}（書き起こしの CPU ${recognizer.cpuThreads}本を残して選ぶ） $profile VAD ${tuning.vadThreshold}/${tuning.vadMinSpeechSeconds}秒")
            vad = SileroVoiceActivity.load(File(asrDir, VAD), profile.phraseSilenceSeconds, tuning.vadThreshold, tuning.vadMinSpeechSeconds)
            return SpeechPipeline(
                vad,
                recognizer,
                profile,
                heavy = model.heavy,
                // セグメンターの経過は話した言葉を含むので、詳しい記録を残すとき（デバッグ版の既定、正式版は詳細設定で選ぶ）だけ出る（Logs.text）
                trace = { message -> Logs.text { "会話の認識: ${message()}" } },
                // ジョブごとの待ち時間と処理時間は、話した言葉を含まないので正式版でも書く（遅さの切り分けに使う）
                timing = { message -> Logs.i("会話の認識: $message") },
                retainUtterances = retainUtterances,
                identifier = identifier,
            )
        } catch (error: Throwable) {
            vad?.close()
            identifier?.close()
            recognizer.close()
            throw error
        }
    }

    private const val VAD = "silero_vad.onnx"
}
