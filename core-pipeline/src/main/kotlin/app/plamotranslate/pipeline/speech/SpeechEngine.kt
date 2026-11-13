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
}

/**
 * 音声を発話に区切って認識する（会話翻訳とテキスト翻訳の音声入力が使う）。実装は中核の [SpeechSegmenter] で、
 * VAD と認識は差し込み口（[VoiceActivity]・[SpeechRecognizer]）から受け取る。
 * 呼び出し側は1本のスレッドから順に音声を渡し、返ってきた出来事を順に処理する。
 */
interface SpeechEngine : AutoCloseable {
    /**
     * 中国語・広東語を、日本語と別の話し手の言語として区別するか。会話の自分か相手が中国語のときだけ true にする
     * （false なら漢字の多い日本語の聞き違いとして日本語と同じに扱い、話し手の交代とみなさない）。
     * 聞いている最中に別のスレッドから変えてよい。
     */
    var separateChinese: Boolean

    /** 16kHz・モノラル・-1〜1のfloat。 */
    fun accept(samples: FloatArray): List<SpeechEvent>

    /** 残っている音声を発話として確定させる（停止時・音声ファイルの終わり）。 */
    fun flush(): List<SpeechEvent>

    /** 途中の音声を捨てて、新しい会話として始め直す。 */
    fun reset()

    companion object {
        const val SAMPLE_RATE = 16_000
    }
}

/** VAD がその音声の後に出した合図。[phraseEnded] は、文の区切りの無音が続いて1つの文が終わったこと。 */
data class VoiceSignal(val speaking: Boolean, val phraseEnded: Boolean)

/** 差し込み口：話しているかと文の終わりを見分ける（VAD）。実装は `core-asr`（Silero VAD）。 */
interface VoiceActivity : AutoCloseable {
    fun accept(samples: FloatArray): VoiceSignal

    /** 残っている音声を区切らせる（停止時）。 */
    fun flush()

    fun reset()
}

/** 認識器が返したそのままの結果。[lang] は判定した言語のコード（判定なしは null）。 */
data class Recognition(val text: String, val lang: String?)

/** 差し込み口：音声を文字にし、言語を判定する。実装は `core-asr`（SenseVoice）。 */
interface SpeechRecognizer : AutoCloseable {
    fun recognize(samples: FloatArray): Recognition
}
