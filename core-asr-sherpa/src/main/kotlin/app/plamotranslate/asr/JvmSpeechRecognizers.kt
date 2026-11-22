package app.plamotranslate.asr

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.speech.LanguageEvidence
import app.plamotranslate.pipeline.speech.LanguageIdentifier
import app.plamotranslate.pipeline.speech.LanguageSource
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.SpeechBreak
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentification
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationConfig
import com.k2fsa.sherpa.onnx.SpokenLanguageIdentificationWhisperConfig
import java.io.File

class JvmWhisperIdentifier(directory: File, threads: Int) : LanguageIdentifier {
    private val detector = SpokenLanguageIdentification(
        assetManager = null,
        config = SpokenLanguageIdentificationConfig(
            whisper = SpokenLanguageIdentificationWhisperConfig(
                encoder = File(directory, files[0]).path,
                decoder = File(directory, files[1]).path,
            ),
            numThreads = threads,
        ),
    )

    override fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>? {
        val stream = detector.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            val code = detector.compute(stream).removePrefix("<|").removeSuffix("|>")
            val group = LanguageEvidence.group(code, separateChinese = true) ?: return null
            return if (group.isNotBlank() && (candidates == null || group in candidates)) mapOf(group to 1f) else null
        } finally {
            stream.release()
        }
    }

    override fun close() = detector.release()

    companion object {
        val files = listOf("whisper-tiny/tiny-encoder.int8.onnx", "whisper-tiny/tiny-decoder.int8.onnx")
    }
}

class JvmSenseVoice(private val directory: File, private val threads: Int, initialLanguage: String? = null) : SpeechRecognizer {
    private val automatic = lazy { create("auto") }
    private var pinned: Pair<String, OfflineRecognizer>? = null

    init {
        // 再生音は言語が決まっている。最初の音声を受け取る前に、その言語の認識器だけを用意する。
        if (initialLanguage != null && initialLanguage in codes) pinned = initialLanguage to create(initialLanguage)
        else automatic.value
    }

    override fun recognize(samples: FloatArray): Recognition = recognizeWith(automatic.value, samples)

    @Synchronized
    override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
        if (language !in codes) return recognize(samples)
        val code = checkNotNull(language)
        val recognizer = pinned?.takeIf { it.first == code }?.second ?: run {
            pinned?.second?.release()
            pinned = null
            create(code).also { pinned = code to it }
        }
        return recognizeWith(recognizer, samples).copy(lang = code, source = LanguageSource.SPECIFIED)
    }

    override fun languageCode(language: Language): String = if (language == Language.ZH_HANT) "yue" else super.languageCode(language)

    private fun recognizeWith(recognizer: OfflineRecognizer, samples: FloatArray): Recognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val code = result.lang.removePrefix("<|").removeSuffix("|>").trim().lowercase().takeIf { it in codes }
            val breaks = result.tokens.mapIndexedNotNull { index, token ->
                val seconds = result.timestamps.getOrNull(index) ?: return@mapIndexedNotNull null
                val punctuation = token.trim()
                val sentence = punctuation in sentenceEnds
                if (!sentence && punctuation !in clauseEnds) null else SpeechBreak((seconds * SpeechEngine.SAMPLE_RATE).toInt(), sentence)
            }
            val text = result.text.trim().let { raw ->
                if (code !in setOf("ja", "zh", "yue")) raw else spaces.replace(raw) { match ->
                    val before = raw.getOrNull(match.range.first - 1)
                    val after = raw.getOrNull(match.range.last + 1)
                    if (before in asciiWord && after in asciiWord) " " else ""
                }
            }
            return Recognition(text, code, breaks)
        } finally {
            stream.release()
        }
    }

    private fun create(language: String) = OfflineRecognizer(
        assetManager = null,
        config = OfflineRecognizerConfig(modelConfig = OfflineModelConfig(
            senseVoice = OfflineSenseVoiceModelConfig(
                model = File(directory, files[0]).path,
                language = language,
                useInverseTextNormalization = true,
            ),
            tokens = File(directory, files[1]).path,
            numThreads = threads,
        )),
    )

    @Synchronized
    override fun close() {
        pinned?.second?.release()
        pinned = null
        if (automatic.isInitialized()) automatic.value.release()
    }

    companion object {
        val files = listOf("sense-voice/model.int8.onnx", "sense-voice/tokens.txt")
        private val codes = setOf("ja", "ko", "en", "zh", "yue")
        private val sentenceEnds = setOf("。", "？", "！", "?", "!", ".")
        private val clauseEnds = setOf("、", "，", ",")
        private val spaces = Regex("\\s+")
        private val asciiWord = ('A'..'Z').toSet() + ('a'..'z') + ('0'..'9')
    }
}
