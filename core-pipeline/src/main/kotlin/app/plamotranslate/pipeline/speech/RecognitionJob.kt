package app.plamotranslate.pipeline.speech

/**
 * 区切り（[SpeechSegmenter]）が認識の担当に頼む仕事（docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md「認識を段に分ける」）。
 * 区切りは仕事を出して待たずに次の音声へ進み、結果（[RecognitionOutput]）を受け取ってから次を決める。
 * 音声は頼んだ時点の写しを持つので、結果が届くまでにためた音声が区切られても影響しない。位置は聞き始めからのサンプル数。
 */
sealed interface RecognitionJob {
    val id: Long

    /** 頼んだカード。結果が届いたときに、まだ同じカードかを見分ける。 */
    val card: Long

    val lane: RecognitionLane
    val priority: RecognitionPriority

    /** 頼んだ音声（写し）。 */
    val samples: FloatArray

    /** 音声の終わりの位置（聞き始めからのサンプル数）。認識の遅れ（今から何秒前の音声を処理しているか）に使う。 */
    val endAt: Long

    /** ログに出す目的（話した言葉は含めない）。 */
    val purpose: String

    /**
     * 書き起こす。[language] があれば、その言語として書き起こす（識別を省く）。無ければ、言語を識別できる認識器は
     * 識別してから書き起こし、識別できなければ [fallback] として書き起こす。
     */
    class Transcribe(
        override val id: Long,
        override val card: Long,
        override val priority: RecognitionPriority,
        override val samples: FloatArray,
        override val endAt: Long,
        override val purpose: String,
        val languages: SpeechLanguages,
        val language: String?,
        val fallback: String?,
    ) : RecognitionJob {
        override val lane get() = RecognitionLane.TRANSCRIBE
    }

    /**
     * 言語だけを判定する。言語だけを識別できる認識器は識別の担当（[RecognitionLane.IDENTIFY]）が、できない認識器は
     * 書き起こしの担当が書き起こして判定する。[evidence] が true なら、話し手の交代の根拠になる判定だけを返す（[LanguageEvidence]）。
     */
    class Language(
        override val id: Long,
        override val card: Long,
        override val lane: RecognitionLane,
        override val samples: FloatArray,
        override val endAt: Long,
        override val purpose: String,
        val languages: SpeechLanguages,
        val evidence: Boolean = true,
    ) : RecognitionJob {
        override val priority get() = RecognitionPriority.LANGUAGE
    }

    /**
     * 言語の継ぎ目を探す。[samples] の中で、[probe] の長さの窓を [step] ずつずらし、[newLang] に変わった最初の窓の
     * 頭の位置（[samples] の中のサンプル数）を返す。前は元の言語、後ろは新しい言語と見て、二分探索で窓の数を減らす。
     */
    class Seam(
        override val id: Long,
        override val card: Long,
        override val lane: RecognitionLane,
        override val samples: FloatArray,
        override val endAt: Long,
        override val purpose: String,
        val languages: SpeechLanguages,
        val newLang: String,
        val probe: Int,
        val step: Int,
    ) : RecognitionJob {
        override val priority get() = RecognitionPriority.LANGUAGE
    }
}

/** 認識の結果。失敗したら空の結果を返す（確定を待たせ続けないため）。 */
sealed interface RecognitionOutput {
    data class Transcribed(val recognition: Recognition) : RecognitionOutput
    data class Identified(val lang: String?) : RecognitionOutput
    data class SeamFound(val at: Int?) : RecognitionOutput
}

/** 認識の担当。担当ごとに1本のスレッドで1件ずつ処理する。重い書き起こしの間も、言語の識別は止まらない。 */
enum class RecognitionLane { TRANSCRIBE, IDENTIFY }

/**
 * 仕事の優先順位（小さいほど先）。確定に要る書き起こしを先にし、途中結果は後回しにする。
 * 確定（区切った残り）は、前のカードの文の書き起こしより先に出ることがある（出来事はカードの順に出すので、結果の順は崩れない）。
 */
enum class RecognitionPriority { FINAL, PHRASE, LANGUAGE, PARTIAL }

/**
 * 判定：認識の仕事の待ち行列（担当ごと）。次に処理する仕事と、要らなくなった仕事を決める。スレッドに依らない。
 * - 優先順位の高いものから、同じ順位は頼んだ順に出す（文の書き起こしは、つなぐ順に届く）。
 * - 閉じたカードの途中結果と言語の判定は捨てる（[dropStale]）。確定と文の書き起こしは捨てない。
 * 途中結果と言語の見張りは、区切りがカードごとに1件ずつしか頼まないので、ここでは間引かない。
 */
class RecognitionQueue {
    private val jobs = mutableListOf<RecognitionJob>()

    val size: Int get() = jobs.size

    fun submit(job: RecognitionJob) {
        jobs += job
    }

    /** [lane] の次の仕事を取り出す。無ければ null。 */
    fun take(lane: RecognitionLane): RecognitionJob? {
        val next = jobs.filter { it.lane == lane }.minByOrNull { it.priority.ordinal } ?: return null
        jobs.remove(next)
        return next
    }

    /** [openCard] 以外のカードの、途中結果と言語の判定を捨てる（確定に要る仕事は残す）。 */
    fun dropStale(openCard: Long?) {
        jobs.removeAll { it.card != openCard && it.priority >= RecognitionPriority.LANGUAGE }
    }

    /** 確定に要る仕事（確定・文の書き起こし）が残っているか。 */
    fun hasUrgent(): Boolean = jobs.any { it.priority <= RecognitionPriority.PHRASE }

    /** まだ処理していない書き起こしのうち、一番古い音声の終わり。無ければ null。 */
    fun oldestTranscribeEnd(): Long? = jobs.filter { it.priority <= RecognitionPriority.PHRASE }.minOfOrNull { it.endAt }

    fun clear() = jobs.clear()
}
