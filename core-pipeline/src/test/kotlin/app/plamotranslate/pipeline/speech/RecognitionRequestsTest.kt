package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Test

/** ジョブの出し方と、音声認識モデルの性質による違い（[RecognitionRequests]）。 */
class RecognitionRequestsTest {
    private val jobs = mutableListOf<RecognitionJob>()
    private var now = 0L

    @Test
    fun `確定にはモデルの判定を使い途中は話者に従い固定入力では常に指定を守る`() {
        val requests = RecognitionRequests(true, true, jobs::add, { now }, JobIds(), true)
        for (priority in listOf(RecognitionPriority.PARTIAL, RecognitionPriority.PHRASE, RecognitionPriority.FINAL)) {
            for (specified in listOf(null, "ko")) {
                requests.transcribe(1, floatArrayOf(), 0, priority, "試験", SpeechLanguages(specified = specified), "ja", "en") {}
                val expected = when {
                    specified != null -> TranscriptLanguage.Specified(specified)
                    priority == RecognitionPriority.PARTIAL -> TranscriptLanguage.Specified("ja")
                    else -> TranscriptLanguage.Unspecified
                }
                assertEquals(expected, (jobs.last() as RecognitionJob.Transcribe).language)
            }
        }
    }

    private fun requests(heavy: Boolean) = RecognitionRequests(identifies = true, heavy = heavy, submit = { jobs += it }, clock = { now }, jobIds = JobIds())

    /** 途中結果を1件頼み、[millis] たってから結果を返す。 */
    private fun RecognitionRequests.partial(millis: Long, paced: Boolean = true) {
        transcribe(0, FloatArray(0), 0, RecognitionPriority.PARTIAL, "途中", SpeechLanguages.ANY, "ja", null, paced) {}
        now += millis * 1_000_000
        onResult(jobs.last().id, RecognitionOutput.Transcribed(Recognition("あ", "ja")))
    }

    @Test
    fun `途中結果の間隔はかかった時間の3倍、書き起こしの重いモデルは2倍で、0点5秒より短くしない`() {
        val light = requests(heavy = false)
        light.partial(millis = 1000)
        assertEquals(SAMPLE_RATE * 3, light.partialInterval)

        val heavy = requests(heavy = true)
        heavy.partial(millis = 1000)
        assertEquals(SAMPLE_RATE * 2, heavy.partialInterval)

        heavy.partial(millis = 10)
        assertEquals(SAMPLE_RATE / 2, heavy.partialInterval)
    }

    @Test
    fun `間隔は頼んだ側が明示したジョブだけで直す`() {
        val r = requests(heavy = false)
        r.partial(millis = 1000, paced = false)
        assertEquals(SAMPLE_RATE / 2, r.partialInterval)

        r.scores(0, FloatArray(0), 0, RecognitionPriority.LANGUAGE, "話し中の区間", SpeechLanguages.ANY, paced = true) { _, _ -> }
        now += 1_000_000_000
        r.onResult(jobs.last().id, RecognitionOutput.Scored(null))
        assertEquals(SAMPLE_RATE * 3, r.watchInterval)
    }

    @Test
    fun `書き起こしの重いモデルだけ、頭から渡す音声の声の始まりより前を除く`() {
        // 話し始めの前に足した1秒のうち、前半は無音、後半から声
        val buffer = SpeechBuffer(SAMPLE_RATE, SAMPLE_RATE * 10)
        buffer.speaking(FloatArray(SAMPLE_RATE / 2) + FloatArray(SAMPLE_RATE / 2) { 0.3f } + FloatArray(SAMPLE_RATE) { 0.3f })
        val preroll = SAMPLE_RATE
        assertEquals(0, requests(heavy = false).captureStart(buffer, 0, buffer.size, preroll))
        val start = requests(heavy = true).captureStart(buffer, 0, buffer.size, preroll)
        assertEquals(true, start > 0 && start < SAMPLE_RATE / 2)
        // 頭から始まらない区間はそのまま
        assertEquals(SAMPLE_RATE, requests(heavy = true).captureStart(buffer, SAMPLE_RATE, buffer.size, preroll))
    }
}
