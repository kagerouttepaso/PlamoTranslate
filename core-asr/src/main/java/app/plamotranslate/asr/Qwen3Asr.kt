package app.plamotranslate.asr

import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineQwen3AsrModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import java.io.File

/**
 * 差し込み口 [SpeechRecognizer] の実装：Qwen3-ASR-0.6B（30言語）。自己回帰で1文字ずつ生成する。
 * sherpa-onnx 2.0.1 はモデルが出す言語の頭（「language Russian」）を取り除き、言語も時刻も返さない
 * （2026-11-15、PCで確かめた。トークン列にも残らない）。言語は前に置く識別（[WhisperLanguageIdentifier]）で決め、
 * [recognizeAs] で指定する。
 *
 * 置き場は `qwen3-asr/` に `conv_frontend.onnx`・`encoder.int8.onnx`・`decoder.int8.onnx`・`tokenizer/`
 * （sherpa-onnx の asr-models の `sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25`）。
 */
class Qwen3Recognizer private constructor(private val recognizer: OfflineRecognizer) : SpeechRecognizer {
    override fun recognize(samples: FloatArray): Recognition = recognizeAs(samples, language = null)

    /** [language] を渡すと、その言語として書き起こさせる。違う言語を指定すると崩れる（アラビア語がローマ字になった）。 */
    override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
        val stream = recognizer.createStream()
        try {
            NAMES[language]?.let { stream.setOption("language", it) }
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            // 終わりの印（<|endoftext|> など）の後ろは、モデルが作り出した続き（2026-11-15のベータで、日本語の後に英語の記事の文を出した）
            val text = recognizer.getResult(stream).text.substringBefore("<|").trim()
            return Recognition(text, lang = null)
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        const val DIR = "qwen3-asr"
        val FILES = listOf("conv_frontend.onnx", "encoder.int8.onnx", "decoder.int8.onnx", "tokenizer/vocab.json")

        /**
         * 6スレッド。端末で5秒前後の発話が4スレッドで1.3〜2.0秒、6スレッドで0.6〜1.5秒だった（2026-11-15）。
         * 翻訳は GPU で動くので、CPU を認識に多く回す。
         */
        const val THREADS = 6

        /** Qwen3-ASR が聞ける30言語のコードと、言語を指定するときの名前。PCで「Russian」「ru」のどちらも受け付けた。 */
        val NAMES = mapOf(
            "zh" to "Chinese", "en" to "English", "yue" to "Cantonese", "ar" to "Arabic", "de" to "German",
            "fr" to "French", "es" to "Spanish", "pt" to "Portuguese", "id" to "Indonesian", "it" to "Italian",
            "ko" to "Korean", "ru" to "Russian", "th" to "Thai", "vi" to "Vietnamese", "ja" to "Japanese",
            "tr" to "Turkish", "hi" to "Hindi", "ms" to "Malay", "nl" to "Dutch", "sv" to "Swedish",
            "da" to "Danish", "fi" to "Finnish", "pl" to "Polish", "cs" to "Czech", "tl" to "Filipino",
            "fa" to "Persian", "el" to "Greek", "hu" to "Hungarian", "mk" to "Macedonian", "ro" to "Romanian",
        )

        fun load(dir: File, threads: Int = THREADS): Qwen3Recognizer = Qwen3Recognizer(
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        qwen3Asr = OfflineQwen3AsrModelConfig(
                            convFrontend = File(dir, "conv_frontend.onnx").path,
                            encoder = File(dir, "encoder.int8.onnx").path,
                            decoder = File(dir, "decoder.int8.onnx").path,
                            tokenizer = File(dir, "tokenizer").path,
                        ),
                        tokens = "",
                        numThreads = threads,
                    ),
                ),
            ),
        )
    }
}
