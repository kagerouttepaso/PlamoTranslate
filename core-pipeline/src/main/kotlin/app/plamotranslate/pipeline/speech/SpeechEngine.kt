package app.plamotranslate.pipeline.speech

/**
 * 話し終わった1つの発話（docs/設計/会話翻訳.md「音声認識」）。
 * [lang] は認識器が判定した言語のコード（"ja"・"ko"・"en"・"zh" など）。判定できなければ null。
 */
data class Utterance(
    val text: String,
    val lang: String?,
    /** 聞き始めからの発話の始まり（ミリ秒）。 */
    val startMs: Long,
    val durationMs: Long,
    /** 発話の終わりを検出してから認識が終わるまでの時間。 */
    val recognizeMs: Long,
)

/** 音声認識から届く出来事。 */
sealed interface SpeechEvent {
    /** 話している最中の聞き取り。同じ発話の間は、届くたびに前の途中結果を置き換える。 */
    data class Partial(val text: String, val lang: String?) : SpeechEvent

    /** 話し終わった発話の確定結果。直前の途中結果を置き換える。 */
    data class Final(val utterance: Utterance) : SpeechEvent

    /** 話し始めたとき（[speaking] = true）と、話し終わったか音声が止んだとき（false）。 */
    data class Speaking(val speaking: Boolean) : SpeechEvent

    /** 区切るボタン（[SpeechEngine.cut]）の区切りを終えた。区切った分の確定（あれば）の後に届く。 */
    data object CutDone : SpeechEvent

    /** 止める前に話した分（[SpeechEngine.flush]）を、すべて確定し終えた。最後に届く。 */
    data object Flushed : SpeechEvent
}

/**
 * 認識の混み具合。[behindSeconds] は、まだ書き起こしていない確定に要る音声が、今からどれだけ前のものか（秒）。
 * [urgent] は、確定に要る書き起こし（区切った残り・文）が残っているか。残っている間、ほかの重い処理（途中訳）を待たせる。
 */
data class RecognitionLoad(val behindSeconds: Float = 0f, val urgent: Boolean = false)

/**
 * 音声を発話に区切って認識する（会話翻訳とテキスト翻訳の音声入力が使う）。部品どうしは、返り値の無い命令と購読する出来事で
 * つなぐ（docs/設計/アーキテクチャ.md「設計の原則」）。命令（[accept]・[cut]・[flush]・[reset]）はすぐ戻り、
 * 出来事は [listener] に届く順に処理する。命令はどのスレッドから呼んでもよいが、音声は話した順に渡す。
 * 実装は中核の [SpeechPipeline]（区切りの状態遷移 [SpeechSegmenter] と、認識の担当のスレッド）。
 */
interface SpeechEngine : AutoCloseable {
    /**
     * 会話の言語（[SpeechLanguages]）。話し手の交代は、この言語の間で変わったときだけ見る。中国語・広東語は、
     * 自分か相手が中国語のときだけ日本語と別の言語として扱う。聞いている最中に別のスレッドから変えてよい。
     */
    var languages: SpeechLanguages

    /** 出来事の届け先。区切りのスレッドから順に呼ばれる。 */
    var listener: (SpeechEvent) -> Unit

    /** 認識の混み具合。変わるたびに区切りのスレッドから呼ばれる。 */
    var loadListener: (RecognitionLoad) -> Unit

    /** 16kHz・モノラル・-1〜1のfloat。 */
    fun accept(samples: FloatArray)

    /** 残っている音声を発話として確定させる（停止時・音声ファイルの終わり）。終えたら [SpeechEvent.Flushed] が届く。 */
    fun flush()

    /**
     * 話している分を確定させ、聞き続ける（利用者が区切るボタンを押したとき）。続けて話した音声は次の発話になる。
     * それまでに渡した音声の位置で区切り、終えたら [SpeechEvent.CutDone] が届く（話していなければ確定は無い）。
     */
    fun cut()

    /** 途中の音声を捨てて、新しい会話として始め直す。 */
    fun reset()

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** VAD がその音声の後に出したシグナル。[phraseEnded] は、文の区切りの無音が続いて1つの文が終わったこと。 */
data class VoiceSignal(val speaking: Boolean, val phraseEnded: Boolean)

/** インターフェース：話しているかと文の終わりを見分ける（VAD）。実装は `core-asr`（Silero VAD）。 */
interface VoiceActivity : AutoCloseable {
    fun accept(samples: FloatArray): VoiceSignal

    /** 残っている音声を区切らせる（停止時）。 */
    fun flush()

    fun reset()
}

/**
 * 認識器が返したそのままの結果。[lang] は判定した言語のコード（判定なしは null）。
 * [breaks] は、認識した文の中の切れ目（句点・読点）が、渡した音声の何サンプル目に当たるか。認識器が時刻を返さなければ空。
 */
data class Recognition(val text: String, val lang: String?, val breaks: List<SpeechBreak> = emptyList())

/** 認識した文の切れ目。[sentence] が true なら文の終わり（句点・疑問符・感嘆符）、false なら読点。 */
data class SpeechBreak(val at: Int, val sentence: Boolean)

/** インターフェース：音声を文字にし、言語を判定する。実装は `core-asr`（SenseVoice・Qwen3-ASR）。 */
interface SpeechRecognizer : AutoCloseable {
    fun recognize(samples: FloatArray): Recognition

    /** 会話の言語を添えて認識する（[SpeechSegmenter] が呼ぶ）。言語の識別を前に置く組み立て（[IdentifiedRecognizer]）が使う。 */
    fun recognize(samples: FloatArray, languages: SpeechLanguages): Recognition = recognize(samples)

    /**
     * 会話の言語に加え、言語を識別できなかったときに使う言語（[fallback]。今のカードの言語）を添えて認識する。
     * 識別できない区間は「話し手は代わっていない」とみなし、その言語として書き起こす。言語を識別しない認識器は無視する。
     */
    fun recognize(samples: FloatArray, languages: SpeechLanguages, fallback: String?): Recognition = recognize(samples, languages)

    /** [language]（言語のコード）として書き起こす。言語を指定できない認識器は無視する。null なら指定しない。 */
    fun recognizeAs(samples: FloatArray, language: String?): Recognition = recognize(samples)

    /**
     * 書き起こさずに言語の点数を出せるか（[scores]）。出せる認識器（言語の識別を前に置く組み立て）は、区切りが言語の判定を
     * 識別の担当に回し、重い書き起こしを省く。出せない認識器（SenseVoice）は、書き起こした結果の言語を点数1の判定として使う
     * （[RecognitionRunner]）。
     */
    val identifies: Boolean get() = false

    /**
     * 会話の言語（[SpeechLanguages.groups] の組。null ならすべて）ごとの確率（合計1。[identifies] が true の認識器）。
     * 判定できなければ null。話し手は、この点数から [SpeakerJudge] が決める。
     */
    fun scores(samples: FloatArray, languages: SpeechLanguages): Map<String, Float>? = null
}

/**
 * VAD が話し始めを見つけるより前に足した音声（[SpeechSegmenter] の頭の補い）の中で、実際に声が始まった位置を探す。
 * 話した言葉を落とさないために頭は常に足すが、書き起こしの重いモデルには、その前の無音まで渡すと時間だけかかる。
 * 足した区間の一番静かな0.1秒を部屋の音（床）とし、床の4倍（12dB）を超えた最初の0.1秒を声の始まりとみなして、
 * その [MARGIN] 前を返す（小さい子音を切らない）。超えるところが無ければ、VAD が見つけた位置の [MARGIN] 前。
 * 値は端末や部屋の音の大きさに依らない比で決める。
 */
object SpeechOnset {
    private const val FRAME = SpeechEngine.SAMPLE_RATE / 10
    const val MARGIN = SpeechEngine.SAMPLE_RATE * 3 / 10
    private const val RATIO = 4.0

    /** [audio] の [0, detected) が足した区間。書き起こしを始める位置（[audio] の中のサンプル数）を返す。 */
    fun start(audio: FloatArray, detected: Int): Int {
        val end = detected.coerceIn(0, audio.size)
        if (end < FRAME * 2) return 0
        val rms = (0 until end / FRAME).map { f ->
            var sum = 0.0
            for (i in f * FRAME until (f + 1) * FRAME) sum += audio[i] * audio[i]
            kotlin.math.sqrt(sum / FRAME)
        }
        val floor = rms.min().coerceAtLeast(1e-5)
        val onset = rms.indexOfFirst { it > floor * RATIO }.takeIf { it >= 0 }?.let { it * FRAME } ?: end
        return (onset - MARGIN).coerceAtLeast(0)
    }
}
