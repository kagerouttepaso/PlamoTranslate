package app.plamotranslate.asr

import app.plamotranslate.pipeline.speech.LanguageSource
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.SpeechBreak
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import app.plamotranslate.pipeline.speech.VoiceActivity
import app.plamotranslate.pipeline.speech.VoiceSignal
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import java.io.File

/**
 * SenseVoice Small のファイル（docs/手順/開発環境.md「モデルの取得と置き場」）。組み立ては [SpeechEngines]。
 */
object SenseVoiceAsr {
    val MODEL_FILES = listOf("sense-voice/model.int8.onnx", "sense-voice/tokens.txt")
}

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

/**
 * インターフェース [SpeechRecognizer] の実装：SenseVoice Small。発話単位の一括認識で、言語タグ（日・韓・英・中・広東語）も返す。
 * ふだんは言語を自分で判定して書き起こす（`language = "auto"`）。
 *
 * 言語を指定した書き起こし（[recognizeAs]。利用者が言語の取り違えを直す書き起こし直し）は、言語を固定した認識器で行う。
 * SenseVoice の言語は認識器の設定にしか無く、書き起こしごとの指定（`setOption("language")`）は効かない（PC で確かめた）。
 * 設定の差し替え（`OfflineRecognizer.setConfig`）は、SenseVoice が読み込み時に直す特徴量の設定（ウィンドウ関数の種類・正規化）を初期値に戻す
 * （sherpa-onnx 2.0.1 のソース）ので使えない。言語を固定した認識器は初めて頼まれたときに読み込み（約240MB、1秒ほど）、
 * 最後に頼まれた言語の1つだけ持つ。書き起こし直しはまれなので、ふだんのメモリは増やさない。
 */
class SenseVoiceRecognizer private constructor(
    private val recognizer: OfflineRecognizer,
    private val pinnedFactory: (String) -> OfflineRecognizer,
) : SpeechRecognizer {
    /** 言語を固定した認識器と、その言語。 */
    private var pinned: Pair<String, OfflineRecognizer>? = null

    override fun recognize(samples: FloatArray): Recognition = recognizeWith(recognizer, samples)

    /** [language] として書き起こす。SenseVoice の知らない言語か null なら、言語を自分で判定する。 */
    @Synchronized
    override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
        if (language == null || language !in KNOWN_LANGS) return recognize(samples)
        val current = pinned?.takeIf { it.first == language }?.second ?: run {
            pinned?.second?.release()
            pinned = null
            pinnedFactory(language).also { pinned = language to it }
        }
        return recognizeWith(current, samples).copy(lang = language, source = LanguageSource.SPECIFIED)
    }

    private fun recognizeWith(r: OfflineRecognizer, samples: FloatArray): Recognition {
        val stream = r.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            r.decode(stream)
            val result = r.getResult(stream)
            val lang = parseLang(result.lang)
            return Recognition(removeCjkSpaces(result.text.trim(), lang), lang, breaksOf(result.tokens, result.timestamps))
        } finally {
            stream.release()
        }
    }

    @Synchronized
    override fun close() {
        pinned?.second?.release()
        pinned = null
        recognizer.release()
    }

    companion object {
        /**
         * SenseVoiceは非自己回帰で軽い。翻訳はGPUで動くので、CPUは認識に4スレッド使う
         * （docs/判断/2026-11-06_会話翻訳では翻訳をGPUに音声認識をCPUに分ける.md）。
         */
        const val THREADS = 4

        fun load(modelDir: File, threads: Int = THREADS): SenseVoiceRecognizer = SenseVoiceRecognizer(
            create(modelDir, threads, AUTO),
        ) { language -> create(modelDir, threads, language) }

        /** [language] は SenseVoice の言語（`auto`・`ja`・`ko`・`en`・`zh`・`yue`）。 */
        private fun create(modelDir: File, threads: Int, language: String) = OfflineRecognizer(
            assetManager = null,
            config = OfflineRecognizerConfig(
                modelConfig = OfflineModelConfig(
                    senseVoice = OfflineSenseVoiceModelConfig(
                        model = File(modelDir, "sense-voice/model.int8.onnx").path,
                        language = language,
                        // 句読点を付け、数字を書き言葉にする。翻訳に渡す文として自然になる
                        useInverseTextNormalization = true,
                    ),
                    tokens = File(modelDir, "sense-voice/tokens.txt").path,
                    numThreads = threads,
                ),
            ),
        )

        private const val AUTO = "auto"

        /** SenseVoiceの言語タグ（例 "<|ja|>"）からコードを取り出す。判定なし（"<|nospeech|>" など）は null。 */
        internal fun parseLang(tag: String?): String? {
            val code = tag?.removePrefix("<|")?.removeSuffix("|>")?.trim()?.lowercase()
            return code?.takeIf { it in KNOWN_LANGS }
        }

        private val KNOWN_LANGS = setOf("ja", "ko", "en", "zh", "yue")

        /**
         * 句読点のトークンとその時刻（秒）から、文の切れ目を作る。SenseVoice は句読点を付けるとき（ITN）、
         * 句読点にも時刻を付けて返す（作者の端末で確かめた。「？」が文の終わりの直後の時刻になる）。
         */
        internal fun breaksOf(tokens: Array<String>, timestamps: FloatArray): List<SpeechBreak> =
            tokens.indices.mapNotNull { i ->
                val t = timestamps.getOrNull(i) ?: return@mapNotNull null
                val token = tokens[i].trim()
                val sentence = token in SENTENCE_ENDS
                if (!sentence && token !in CLAUSE_ENDS) return@mapNotNull null
                SpeechBreak((t * SpeechEngine.SAMPLE_RATE).toInt(), sentence)
            }

        private val SENTENCE_ENDS = setOf("。", "？", "！", "?", "!", ".")
        private val CLAUSE_ENDS = setOf("、", "，", ",")

        /**
         * SenseVoiceは語の区切りに空白を入れることがある（「うち の 中学 は」）。
         * 日本語・中国語は語の間に空白を置かないので、英数字どうしの間の空白だけ残して取り除く。
         * 韓国語は語の間の空白（分かち書き）が正しい場合もあるので触らない。
         */
        internal fun removeCjkSpaces(text: String, lang: String?): String {
            if (lang !in setOf("ja", "zh", "yue")) return text
            return SPACES.replace(text) { m ->
                val before = text.getOrNull(m.range.first - 1)
                val after = text.getOrNull(m.range.last + 1)
                if (before.isAsciiWord() && after.isAsciiWord()) " " else ""
            }
        }

        private val SPACES = Regex("\\s+")

        private fun Char?.isAsciiWord() = this != null && (this in 'A'..'Z' || this in 'a'..'z' || this in '0'..'9')
    }
}
