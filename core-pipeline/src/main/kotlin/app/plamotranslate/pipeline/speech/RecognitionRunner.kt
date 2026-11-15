package app.plamotranslate.pipeline.speech

/**
 * 認識の仕事（[RecognitionJob]）を1件処理する（認識の担当のスレッドが呼ぶ。試験では同じスレッドから呼ぶ）。
 * 差し込み口の [recognizer] を、仕事の種類に合わせて呼び分ける。
 */
class RecognitionRunner(private val recognizer: SpeechRecognizer) {
    /** 仕事を1件処理する（担当のスレッド）。 */
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
    }

    /**
     * 会話の言語ごとの点数。点数を出せる認識器はその点数を、出せない認識器は書き起こした結果の言語を点数1とする。
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
     * 言語の継ぎ目：窓を [RecognitionJob.Seam.step] ずつずらし、新しい言語になった最初の窓を二分探索で探す
     * （前は元の言語、後ろは新しい言語と見る。10個の窓を4回ほどの判定で済ませる）。見つけたら、継ぎ目から後ろの全体を
     * 文の全体と同じ確からしさ（[SpeakerJudge.PHRASE]）で確かめる。短い窓は外れやすいので、窓だけで区切らない。
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
        val at = lo * job.step
        val after = job.samples.copyOfRange(at, job.samples.size)
        return at.takeIf { decide(after, job.languages, strict = true, SpeakerJudge.PHRASE) == job.newLang }
    }

    companion object {
        /** 失敗した仕事の結果（空）。確定を待たせ続けないため。 */
        fun empty(job: RecognitionJob): RecognitionOutput = when (job) {
            is RecognitionJob.Transcribe -> RecognitionOutput.Transcribed(Recognition("", null))
            is RecognitionJob.Language -> RecognitionOutput.Scored(null)
            is RecognitionJob.Seam -> RecognitionOutput.SeamFound(null)
        }
    }
}
