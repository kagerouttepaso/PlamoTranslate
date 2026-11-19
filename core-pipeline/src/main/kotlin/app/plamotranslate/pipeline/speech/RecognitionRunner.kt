package app.plamotranslate.pipeline.speech

/**
 * 認識のジョブ（[RecognitionJob]）を1件処理する（認識のワーカーのスレッドが呼ぶ。試験では同じスレッドから呼ぶ）。
 * 認識のインターフェース [recognizer] を、ジョブの種類に合わせて呼び分ける。
 */
class RecognitionRunner(private val recognizer: SpeechRecognizer) {
    /** ジョブを1件処理する（ワーカーのスレッド）。 */
    fun run(job: RecognitionJob): RecognitionOutput = when (job) {
        is RecognitionJob.Transcribe -> RecognitionOutput.Transcribed(
            // 話し手の言語が分かっていれば、識別を省いてその言語として書き起こす
            if (job.language != null && recognizer.identifies) {
                recognizer.recognizeAs(job.samples, job.language)
            } else {
                recognizer.recognize(job.samples, job.languages, job.fallback)
            },
        )
        is RecognitionJob.Language -> scored(job.samples, job.languages, strict = true)
        is RecognitionJob.Seam -> RecognitionOutput.SeamFound(seam(job))
        is RecognitionJob.Retranscribe -> RecognitionOutput.Transcribed(recognizer.recognizeAs(job.samples, recognizer.languageCode(job.language)))
    }

    /**
     * 会話の言語ごとのスコア。スコアを出せる認識器はそのスコアを、出せない認識器は書き起こした結果の言語をスコア1とする。
     * [strict] なら、書き起こした結果の言語は、話し手の根拠になるもの（[LanguageEvidence.of]：文字の種類が合い、1語より多い）だけを使う。
     */
    private fun scored(samples: FloatArray, languages: SpeechLanguages, strict: Boolean): RecognitionOutput.Scored {
        if (recognizer.identifies) return RecognitionOutput.Scored(recognizer.scores(samples, languages))
        val r = TranscriptCleanup.clean(recognizer.recognize(samples, languages))
        val lang = if (strict) {
            LanguageEvidence.of(r, languages)
        } else {
            LanguageEvidence.group(r.lang, languages.separateChinese)?.takeIf { languages.groups == null || it in languages.groups }
        }
        return RecognitionOutput.Scored(lang?.let { mapOf(it to 1f) }, r)
    }

    private fun decide(samples: FloatArray, languages: SpeechLanguages, strict: Boolean, confidence: Float): String? =
        SpeakerJudge.decide(scored(samples, languages, strict).scores, languages, confidence)

    /**
     * 言語の境界：区間を [RecognitionJob.Seam.step] ずつずらし、新しい言語になった最初の区間を二分探索で探す
     * （前は元の言語、後ろは新しい言語と見る。10個の区間を4回ほどの判定で済ませる）。交代かどうかは頼んだ側（話し手の時系列の
     * 連続区間）が決めているので、ここは位置を探すだけにする。
     */
    private fun seam(job: RecognitionJob.Seam): Int? {
        val count = (job.samples.size - job.probe) / job.step + 1
        if (count <= 0) return null
        fun matches(i: Int): Boolean {
            val from = i * job.step
            val window = job.samples.copyOfRange(from, minOf(from + job.probe, job.samples.size))
            return decide(window, job.languages, strict = false, SpeakerJudge.WINDOW) == job.newLang
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
