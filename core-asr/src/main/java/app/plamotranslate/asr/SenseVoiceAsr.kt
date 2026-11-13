package app.plamotranslate.asr

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechBreak
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import app.plamotranslate.pipeline.speech.SpeechSegmenter
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
 * 音声認識の組み立て（docs/設計/会話翻訳.md「音声認識」）。中核の区切り（[SpeechSegmenter]）に、
 * sherpa-onnx の Silero VAD（[SileroVoiceActivity]）と SenseVoice Small（[SenseVoiceRecognizer]）を差す。
 *
 * [modelDir] には次を置く（docs/手順/開発環境.md「モデルの取得と置き場」）。
 * - `sense-voice/model.int8.onnx`、`sense-voice/tokens.txt`
 * - `silero_vad.onnx`
 */
object SenseVoiceAsr {
    private val FILES = listOf("sense-voice/model.int8.onnx", "sense-voice/tokens.txt", "silero_vad.onnx")

    /** モデルが揃っていなければ、足りないファイルのパスを返す。 */
    fun missingFiles(modelDir: File): List<String> =
        FILES.map { File(modelDir, it) }.filterNot { it.canRead() }.map { it.path }

    /** モデルを読み込む。数秒かかるので画面を開いたときに1回だけ呼ぶ。[profile] は用途ごとの区切り方。 */
    fun load(modelDir: File, profile: SegmentationProfile = SegmentationProfile.CONVERSATION): SpeechEngine {
        val missing = missingFiles(modelDir)
        require(missing.isEmpty()) { "音声認識のモデルがありません: ${missing.joinToString()}" }
        return SpeechSegmenter(
            SileroVoiceActivity.load(File(modelDir, "silero_vad.onnx"), profile.phraseSilenceSeconds),
            SenseVoiceRecognizer.load(modelDir),
            profile,
            // 区切りの経過は話した言葉を含むので、デバッグ版だけに出る（Logs.text）
            trace = { message -> Logs.text { "会話の認識: ${message()}" } },
        )
    }
}

/**
 * 差し込み口 [VoiceActivity] の実装：Silero VAD。話しているかと、文の区切りの無音（phraseSilenceSeconds）で文が終わったかを返す。
 * VADが切り出した音声は使わない（区切りの合図として読むだけ。認識する音声は中核がためる）。
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
        fun load(model: File, phraseSilenceSeconds: Float): SileroVoiceActivity = SileroVoiceActivity(
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = model.path,
                        threshold = 0.5f,
                        // VADは文の区切りの無音で「文」を区切る。カードの確定（話し終わり、言語の交代）は中核が決める
                        minSilenceDuration = phraseSilenceSeconds,
                        minSpeechDuration = 0.25f,
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

/** 差し込み口 [SpeechRecognizer] の実装：SenseVoice Small。発話単位の一括認識で、言語タグ（日・韓・英・中・広東語）も返す。 */
class SenseVoiceRecognizer private constructor(private val recognizer: OfflineRecognizer) : SpeechRecognizer {
    override fun recognize(samples: FloatArray): Recognition {
        val stream = recognizer.createStream()
        try {
            stream.acceptWaveform(samples, SpeechEngine.SAMPLE_RATE)
            recognizer.decode(stream)
            val result = recognizer.getResult(stream)
            val lang = parseLang(result.lang)
            return Recognition(removeCjkSpaces(result.text.trim(), lang), lang, breaksOf(result.tokens, result.timestamps))
        } finally {
            stream.release()
        }
    }

    override fun close() = recognizer.release()

    companion object {
        /**
         * SenseVoiceは非自己回帰で軽い。翻訳はGPUで動くので、CPUは認識に4スレッド使う
         * （REDMAGICのCPUクロック上限は docs/現在地.md）。
         */
        private const val THREADS = 4

        fun load(modelDir: File): SenseVoiceRecognizer = SenseVoiceRecognizer(
            OfflineRecognizer(
                assetManager = null,
                config = OfflineRecognizerConfig(
                    modelConfig = OfflineModelConfig(
                        senseVoice = OfflineSenseVoiceModelConfig(
                            model = File(modelDir, "sense-voice/model.int8.onnx").path,
                            language = "auto",
                            // 句読点を付け、数字を書き言葉にする。翻訳に渡す文として自然になる
                            useInverseTextNormalization = true,
                        ),
                        tokens = File(modelDir, "sense-voice/tokens.txt").path,
                        numThreads = THREADS,
                    ),
                ),
            ),
        )

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
