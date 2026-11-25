package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

sealed interface TranscriptLanguage {
    data object Unspecified : TranscriptLanguage
    data class Specified(val code: String) : TranscriptLanguage
    /** モデルが会話の言語を返したら採用し、それ以外なら [fallback] を指定して書き直す。 */
    data class Conversation(val groups: Set<String>, val fallback: String) : TranscriptLanguage
    data class Identify(val candidates: SpeechLanguages, val fallback: String?) : TranscriptLanguage
}

/**
 * 認識のジョブ（[RecognitionJob]）を1件処理する（認識のワーカーのスレッドが呼ぶ。試験では同じスレッドから呼ぶ）。
 * 認識のインターフェース [recognizer] を、ジョブの種類に合わせて呼び分ける。
 */
class RecognitionRunner(private val recognizer: SpeechRecognizer, private val identifier: LanguageIdentifier? = null) {
    private val identified = identifier?.let { IdentifiedRecognizer(it, recognizer) }
    /** ジョブを1件処理する（ワーカーのスレッド）。 */
    suspend fun run(job: RecognitionJob): RecognitionOutput = when (job) {
        is RecognitionJob.Transcribe -> RecognitionOutput.Transcribed(
            // 話し手の言語が分かっていれば、識別を省いてその言語として書き起こす
            when (val language = job.language) {
                TranscriptLanguage.Unspecified -> recognizer.transcribe(job.samples, null, job.priority)
                is TranscriptLanguage.Specified -> recognizer.transcribe(job.samples, language.code, job.priority)
                is TranscriptLanguage.Conversation -> {
                    val result = recognizer.transcribe(job.samples, null, job.priority)
                    if (LanguageEvidence.group(result.lang, separateChinese = true) in language.groups) result
                    else {
                        currentCoroutineContext().ensureActive()
                        recognizer.transcribe(job.samples, language.fallback, job.priority)
                    }
                }
                is TranscriptLanguage.Identify -> checkNotNull(identified).identifyAndRecognize(job.samples, language.candidates, language.fallback, job.priority)
            },
        )
        is RecognitionJob.Language -> scored(job.samples, job.languages, job.priority, strict = true)
        is RecognitionJob.Seam -> RecognitionOutput.SeamFound(seam(job))
        is RecognitionJob.Retranscribe -> RecognitionOutput.Transcribed(recognizer.transcribe(job.samples, recognizer.languageCode(job.language), job.priority))
    }

    /**
     * 会話の言語ごとのスコア。スコアを出せる認識器はそのスコアを、出せない認識器は書き起こした結果の言語をスコア1とする。
     * [strict] なら、書き起こした結果の言語は、話し手の根拠になるもの（[LanguageEvidence.of]：文字の種類が合い、1語より多い）だけを使う。
     */
    private suspend fun scored(samples: FloatArray, languages: SpeechLanguages, priority: RecognitionPriority, strict: Boolean): RecognitionOutput.Scored {
        if (identifier != null) return RecognitionOutput.Scored(identifier.groupedScores(samples, languages))
        val r = TranscriptCleanup.clean(recognizer.transcribe(samples, null, priority))
        val lang = if (strict) {
            LanguageEvidence.of(r, languages)
        } else {
            LanguageEvidence.group(r.lang, languages.separateChinese)?.takeIf { languages.groups == null || it in languages.groups }
        }
        return RecognitionOutput.Scored(lang?.let { mapOf(it to 1f) }, r)
    }

    /**
     * 言語の境界：区間を [RecognitionJob.Seam.step] ずつずらし、新しい言語になった最初の区間を二分探索で探す
     * （前は元の言語、後ろは新しい言語と見る。10個の区間を4回ほどの判定で済ませる）。交代かどうかは頼んだ側（話し手の時系列の
     * 連続区間）が決めているので、ここは位置を探すだけにする。
     */
    private suspend fun seam(job: RecognitionJob.Seam): Int? {
        val count = (job.samples.size - job.probe) / job.step + 1
        if (count <= 0) return null
        suspend fun matches(i: Int): Boolean {
            val from = i * job.step
            val window = job.samples.copyOfRange(from, minOf(from + job.probe, job.samples.size))
            val scores = scored(window, job.languages, job.priority, strict = false).scores
            return SpeakerJudge.decide(scores, job.languages, SpeakerJudge.WINDOW) == job.newLang
        }
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches(mid)) hi = mid else lo = mid + 1
        }
        if (lo >= count) return null
        return lo * job.step
    }

    companion object {
        /** 失敗したジョブ・捨てたジョブの結果（空）。確定を待たせ続けないため。 */
        fun empty(job: RecognitionJob): RecognitionOutput = when (job) {
            is RecognitionJob.Transcribe, is RecognitionJob.Retranscribe -> RecognitionOutput.Transcribed(Recognition("", null))
            is RecognitionJob.Language -> RecognitionOutput.Scored(null)
            is RecognitionJob.Seam -> RecognitionOutput.SeamFound(null)
        }
    }
}
