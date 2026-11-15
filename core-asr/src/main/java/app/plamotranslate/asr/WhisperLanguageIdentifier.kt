package app.plamotranslate.asr

import app.plamotranslate.pipeline.speech.LanguageIdentifier
import app.plamotranslate.pipeline.speech.SpeechEngine
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.File

/**
 * 話された言語の識別（既定の実装。docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md「直した後の構造」）。
 * sherpa-onnx の SpokenLanguageIdentification で、Whisper tiny（99言語）を使う。認識モデルによらず使える。
 *
 * [dir] には `tiny-encoder.int8.onnx`・`tiny-decoder.int8.onnx` を置く（sherpa-onnx の asr-models の `sherpa-onnx-whisper-tiny`）。
 */
class WhisperLanguageIdentifier private constructor(private val lid: SpokenLanguageIdentification) : LanguageIdentifier {
    /**
     * 言語のコード（"ja"・"ru" など Whisper の表記）。判定できなければ null。端末で5秒の発話に0.23〜0.3秒。
     * 識別の担当と書き起こしの担当（カードの言語が無いときに識別してから書き起こす）の両方から呼ばれるので、1件ずつにする。
     */
    @Synchronized
    override fun identify(samples: FloatArray): String? {
        val stream = lid.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            return lid.compute(stream).trim().takeIf { it.isNotEmpty() }
        } finally {
            stream.release()
        }
    }

    override fun close() = lid.release()

    companion object {
        const val DIR = "whisper-tiny"
        val FILES = listOf("tiny-encoder.int8.onnx", "tiny-decoder.int8.onnx")

        fun missingFiles(dir: File): List<String> = FILES.map { File(dir, it) }.filterNot { it.canRead() }.map { it.path }

        fun load(dir: File, threads: Int = 2): WhisperLanguageIdentifier = WhisperLanguageIdentifier(
            SpokenLanguageIdentification(
                assetManager = null,
                config = SpokenLanguageIdentificationConfig(
                    whisper = SpokenLanguageIdentificationWhisperConfig(
                        encoder = File(dir, "tiny-encoder.int8.onnx").path,
                        decoder = File(dir, "tiny-decoder.int8.onnx").path,
                    ),
                    numThreads = threads,
                ),
            ),
        )
    }
}
