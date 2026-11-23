package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE

/**
 * [SpeechSegmenter] が認識のジョブ（[RecognitionJob]）を出し、結果を受け取る口。ジョブの番号と結果を渡す関数を持ち、
 * 結果は [onResult] で届いた順に渡す。認識した文字の聞き違いは、渡す前に落とす（[TranscriptCleanup]）。
 *
 * 音声認識モデルの性質による違いは、ここだけで扱う。
 * - 書き起こさずにスコアを出せる認識器か（[identifies]）：出せればスコアは識別のワーカーに回す。
 * - 書き起こしの重いモデルか（[heavy]、Qwen3-ASR）：話し始めの前に足した音声のうち、声の始まり（[SpeechOnset]）より前を
 *   渡さず（書き起こしの時間は音声の長さに比例する）、途中結果の間隔を詰める。
 *
 * 途中結果と話し中の区間の間隔（[partialInterval]・[watchInterval]）は、かかった時間（待ち時間を含む）の何倍かより
 * 短くしない（音声の処理は止まらないので、CPU の取り分だけを決める）。
 * 状態を持つので、[SpeechSegmenter] と同じ1本のスレッドから呼ぶ。
 */
internal class RecognitionRequests(
    private val identifies: Boolean,
    private val heavy: Boolean,
    private val submit: (RecognitionJob) -> Unit,
    private val clock: () -> Long,
    /** ジョブの番号の採番（同じエンジンの中でほかにジョブを出す部品と同じものを渡す）。 */
    private val jobIds: JobIds,
) {
    /** スコアを頼むワーカー。 */
    private val languageLane = if (identifies) RecognitionLane.IDENTIFY else RecognitionLane.TRANSCRIBE

    private val handlers = HashMap<Long, (RecognitionOutput) -> Unit>()

    /**
     * 話し中の区間の間隔（話した音声のサンプル数）。どのモデルも、最短0.5秒で、監視1回にかかった時間の3倍より短くはしない。
     * スコアを書き起こしのワーカーで出すモデル（SenseVoice）では、ワーカーが混むと延びて途中結果の時間を残す。
     */
    var watchInterval = WATCH_INTERVAL_SAMPLES
        private set

    /** 途中結果を頼む間隔（サンプル数）。 */
    var partialInterval = MIN_PARTIAL_INTERVAL
        private set

    /** 頼んだジョブの結果を、頼んだときの関数に渡す。忘れたジョブ（[clear]）の結果は捨てる。 */
    fun onResult(id: Long, output: RecognitionOutput) {
        handlers.remove(id)?.invoke(output)
    }

    /** 頼んだジョブの結果を待たない（聞き直し・閉じた）。間隔も初めに戻すのは [resetPacing]。 */
    fun clear() = handlers.clear()

    fun resetPacing() {
        partialInterval = MIN_PARTIAL_INTERVAL
        watchInterval = WATCH_INTERVAL_SAMPLES
    }

    /**
     * カード [card] の [samples]（聞き始めから [end] で終わる音声）を [language] として書き起こしてもらう（識別を省く）。
     * null なら識別してもらい、識別できなければ [fallback] として書き起こす。[paced] なら（途中結果）、結果が届いたら、
     * かかった時間から次の途中結果までの間隔を決める。
     */
    fun transcribe(
        card: Long,
        samples: FloatArray,
        end: Long,
        priority: RecognitionPriority,
        purpose: String,
        languages: SpeechLanguages,
        language: String?,
        fallback: String?,
        paced: Boolean = false,
        cancelled: () -> Unit = {},
        then: (Recognition) -> Unit,
    ) {
        val submittedAt = clock()
        val instruction = when {
            language != null && (identifies || languages.specified != null) -> TranscriptLanguage.Specified(language)
            identifies -> TranscriptLanguage.Identify(languages, fallback)
            else -> TranscriptLanguage.Unspecified
        }
        job({ id -> RecognitionJob.Transcribe(id, card, priority, samples, end, purpose, instruction) }) { out ->
            if (out == RecognitionOutput.Cancelled) return@job cancelled()
            if (paced) {
                partialInterval = maxOf(MIN_PARTIAL_INTERVAL, costSamples(submittedAt) * if (heavy) HEAVY_PARTIAL_COST_RATIO else PARTIAL_COST_RATIO)
            }
            then(TranscriptCleanup.clean((out as? RecognitionOutput.Transcribed)?.recognition ?: Recognition("", null)))
        }
    }

    /**
     * [samples]（聞き始めから [end] で終わる音声）の、会話の言語ごとのスコアを出してもらう。書き起こしてスコアを出した認識器は、
     * その書き起こし（後処理の後）も返す。[paced] なら（話し中の区間）、結果が届いたら、かかった時間から次の区間までの間隔を決める。
     */
    fun scores(
        card: Long,
        samples: FloatArray,
        end: Long,
        priority: RecognitionPriority,
        purpose: String,
        languages: SpeechLanguages,
        paced: Boolean = false,
        then: (RecognitionOutput.Scored?, Recognition?) -> Unit,
    ) {
        val submittedAt = clock()
        job({ id -> RecognitionJob.Language(id, card, languageLane, priority, samples, end, purpose, languages) }) { out ->
            if (paced) {
                watchInterval = maxOf(WATCH_INTERVAL_SAMPLES, costSamples(submittedAt) * WATCH_COST_RATIO)
            }
            val scored = out as? RecognitionOutput.Scored
            then(scored, scored?.recognition?.let(TranscriptCleanup::clean))
        }
    }

    /** [samples] の中で [newLang] に代わった位置を探してもらう（[RecognitionJob.Seam]）。見つからなければ null。 */
    fun seam(
        card: Long,
        samples: FloatArray,
        end: Long,
        purpose: String,
        languages: SpeechLanguages,
        newLang: String,
        probe: Int,
        step: Int,
        then: (Int?) -> Unit,
    ) {
        job({ id -> RecognitionJob.Seam(id, card, languageLane, samples, end, purpose, languages, newLang, probe, step) }) { out ->
            then((out as? RecognitionOutput.SeamFound)?.at)
        }
    }

    /**
     * ためた音声の [from]〜[end] を認識に渡すときの始まり。書き起こしの重いモデルは、話し始めの前に足した音声（[buffer] の
     * 頭から [preroll] まで）のうち、声の始まりより前を渡さない。
     */
    fun captureStart(buffer: SpeechBuffer, from: Int, end: Int, preroll: Int): Int {
        val start = from.coerceIn(0, end)
        if (!heavy || start != 0 || preroll <= 0) return start
        return SpeechOnset.start(buffer.slice(0, preroll), preroll).coerceAtMost(end)
    }

    private fun job(make: (Long) -> RecognitionJob, handler: (RecognitionOutput) -> Unit) {
        val id = jobIds.next()
        handlers[id] = handler
        submit(make(id))
    }

    /** [submittedAt] からかかった時間を、音声のサンプル数に直す。 */
    private fun costSamples(submittedAt: Long): Int = ((clock() - submittedAt) / 1_000_000 * SAMPLE_RATE / 1000).toInt()

    private companion object {
        /** 途中結果は0.5秒ごとより頻繁には頼まない。 */
        const val MIN_PARTIAL_INTERVAL = SAMPLE_RATE / 2

        /** 話している最中に区間のスコアを頼む最短の間隔（話した音声の長さ）。途中結果の間隔とは別に数える。 */
        const val WATCH_INTERVAL_SAMPLES = SAMPLE_RATE / 2

        /** 監視の間隔を、監視にかかった時間の何倍より短くしないか（監視がワーカーの時間の3分の1より多くを取らない）。 */
        const val WATCH_COST_RATIO = 3

        /** 途中結果の間隔を、かかった時間の何倍にするか（書き起こしの重いモデルは2倍、ほかは3倍）。 */
        const val HEAVY_PARTIAL_COST_RATIO = 2
        const val PARTIAL_COST_RATIO = 3
    }
}
