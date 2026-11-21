package app.plamotranslate.asr

import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.VoiceActivity
import app.plamotranslate.pipeline.speech.VoiceSignal
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * インターフェース [VoiceActivity] の実装：Silero VAD。話しているかと、文の区切りの無音（phraseSilenceSeconds）で文が終わったかを返す。
 * VADが切り出した音声は使わない（区切りのシグナルとして読むだけ。認識する音声は中核がためる）。
 */
class SileroVoiceActivity private constructor(private val vad: Vad) : VoiceActivity {
    override fun accept(samples: FloatArray): VoiceSignal {
        vad.acceptWaveform(samples)
        val speaking = vad.isSpeechDetected()
        val phraseEnded = !vad.empty()
        while (!vad.empty()) vad.pop()
        return VoiceSignal(speaking, phraseEnded)
    }

    override fun flush() {
        vad.flush()
        while (!vad.empty()) vad.pop()
    }

    override fun reset() = vad.reset()

    override fun close() = vad.release()

    companion object {
        const val DEFAULT_THRESHOLD = 0.5f
        const val DEFAULT_MIN_SPEECH_SECONDS = 0.25f

        /**
         * [threshold] は話しているとみなす確率のしきい値、[minSpeechSeconds] は話し始めとみなす最短の長さ。
         * 作者の録音で、話し始めの検出が0.7〜1.3秒遅れた（頭は前に足して補っている）。縮められるかをデバッグ版の設定で試す。
         */
        fun load(
            model: File,
            phraseSilenceSeconds: Float,
            threshold: Float = DEFAULT_THRESHOLD,
            minSpeechSeconds: Float = DEFAULT_MIN_SPEECH_SECONDS,
        ): SileroVoiceActivity = SileroVoiceActivity(
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model.path,
                        threshold = threshold,
                        // VADは文の区切りの無音で「文」を区切る。カードの確定（話し終わり、言語の交代）は中核が決める
                        minSilenceDuration = phraseSilenceSeconds,
                        minSpeechDuration = minSpeechSeconds,
                        windowSize = 512,
                        // 長い発話はVADでは区切らず、中核が静かなところを探して区切る。
                        // VADに区切らせると、次の話し始めを検出し直す間の音声が抜けた
                        maxSpeechDuration = 600f,
                    ),
                    sampleRate = SpeechEngine.SAMPLE_RATE,
                    numThreads = 1,
                ),
            ),
        )
    }
}
