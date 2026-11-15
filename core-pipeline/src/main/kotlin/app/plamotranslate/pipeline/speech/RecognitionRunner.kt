package app.plamotranslate.pipeline.speech

/**
 * 認識の仕事（[RecognitionJob]）を1件処理する（認識の担当のスレッドが呼ぶ。試験では同じスレッドから呼ぶ）。
 * 差し込み口の [recognizer] を、仕事の種類に合わせて呼び分ける。
 */
class RecognitionRunner(private val recognizer: SpeechRecognizer) {
    /** 仕事を1件処理する（担当のスレッド）。 */
    fun run(job: RecognitionJob): RecognitionOutput = when (job) {
        is RecognitionJob.Transcribe -> RecognitionOutput.Transcribed(
            // カードの言語が分かっていれば、識別を省いてその言語として書き起こす
            if (job.language != null && recognizer.identifies) {
                recognizer.recognizeAs(job.samples, job.language)
            } else {
                recognizer.recognize(job.samples, job.languages, job.fallback)
            },
        )
        is RecognitionJob.Language -> RecognitionOutput.Identified(languageOf(job.samples, job.languages, job.evidence))
        is RecognitionJob.Seam -> RecognitionOutput.SeamFound(seam(job))
    }

    /** 言語だけを判定する。識別できる認識器は識別し、できなければ書き起こした結果の言語を使う。 */
    private fun languageOf(samples: FloatArray, languages: SpeechLanguages, evidence: Boolean): String? {
        if (recognizer.identifies) return recognizer.identify(samples, languages)
        val r = TranscriptCleanup.clean(recognizer.recognize(samples, languages))
        return if (evidence) LanguageEvidence.of(r, languages) else LanguageEvidence.group(r.lang, languages.separateChinese)
    }

    /**
     * 言語の継ぎ目：窓を [RecognitionJob.Seam.step] ずつずらし、新しい言語になった最初の窓を二分探索で探す
     * （前は元の言語、後ろは新しい言語と見る。10個の窓を4回ほどの判定で済ませる）。継ぎ目探しは交代の根拠を決めるのではないので、
     * 根拠の決まり（[LanguageEvidence.of]）は通さない。
     */
    private fun seam(job: RecognitionJob.Seam): Int? {
        val count = (job.samples.size - job.probe) / job.step + 1
        if (count <= 0) return null
        fun matches(i: Int): Boolean {
            val from = i * job.step
            val window = job.samples.copyOfRange(from, minOf(from + job.probe, job.samples.size))
            return languageOf(window, job.languages, evidence = false) == job.newLang
        }
        var lo = 0
        var hi = count
        while (lo < hi) {
            val mid = (lo + hi) / 2
            if (matches(mid)) hi = mid else lo = mid + 1
        }
        return if (lo < count) lo * job.step else null
    }

    companion object {
        /** 失敗した仕事の結果（空）。確定を待たせ続けないため。 */
        fun empty(job: RecognitionJob): RecognitionOutput = when (job) {
            is RecognitionJob.Transcribe -> RecognitionOutput.Transcribed(Recognition("", null))
            is RecognitionJob.Language -> RecognitionOutput.Identified(null)
            is RecognitionJob.Seam -> RecognitionOutput.SeamFound(null)
        }
    }
}
