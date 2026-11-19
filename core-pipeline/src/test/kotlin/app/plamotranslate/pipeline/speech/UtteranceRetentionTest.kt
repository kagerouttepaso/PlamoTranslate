package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 確定した発話の音声の取り置きと書き起こし直し（[UtteranceRetention]）。認識のワーカーの代わりに、出したジョブを手で返す。 */
class UtteranceRetentionTest {
    private val events = mutableListOf<SpeechEvent>()
    private val jobs = mutableListOf<RecognitionJob.Retranscribe>()
    private val retention = UtteranceRetention(capacity = 3, emit = { events += it }, submit = { jobs += it as RecognitionJob.Retranscribe })

    private fun final(id: Long) {
        retention.retain(id, FloatArray(10) { id.toFloat() })
        retention.finalEmitted(Utterance(id, "가나다", "ja", startMs = id * 1000, durationMs = 500, recognizeMs = 0))
    }

    private fun answer(job: RecognitionJob.Retranscribe, text: String) =
        retention.onResult(job, RecognitionOutput.Transcribed(Recognition(text, "ko", source = LanguageSource.SPECIFIED)))

    @Test
    fun 直近の発話だけ取り置き古いものは手放して知らせる() {
        (0L..3L).forEach(::final)
        assertEquals(listOf(SpeechEvent.Released(0)), events)
        retention.retranscribe(0, Language.KO)
        assertEquals(SpeechEvent.RetranscribeFailed(0, RetranscribeFailure.RELEASED), events.last())
        assertTrue(jobs.isEmpty())
    }

    @Test
    fun 取り置いた音声を指定した言語で書き起こし直す() {
        final(5)
        retention.retranscribe(5, Language.KO)
        val job = jobs.single()
        assertEquals(Language.KO, job.language)
        assertEquals(5f, job.samples.first())
        answer(job, "가나다라")
        val done = events.last() as SpeechEvent.Retranscribed
        assertEquals(Utterance(5, "가나다라", "ko", 5000, 500, done.utterance.recognizeMs), done.utterance)
    }

    @Test
    fun 何も聞き取れなければ書き起こし直せなかったと知らせる() {
        final(1)
        retention.retranscribe(1, Language.KO)
        answer(jobs.single(), "")
        assertEquals(SpeechEvent.RetranscribeFailed(1, RetranscribeFailure.NOTHING_HEARD), events.last())
    }

    @Test
    fun 待っている間に重ねて頼まれたら1つの結果にまとめる() {
        final(1)
        retention.retranscribe(1, Language.KO)
        retention.retranscribe(1, Language.KO)
        assertEquals(1, jobs.size)
        answer(jobs.single(), "가")
        assertEquals(1, events.count { it is SpeechEvent.Retranscribed })
    }

    @Test
    fun 始め直したら待っている書き起こし直しを出し直し前のジョブの結果は使わない() {
        final(1)
        retention.retranscribe(1, Language.KO)
        val before = jobs.single()
        retention.reset()
        val after = jobs.last()
        assertTrue(after.id != before.id)
        answer(before, "古い")
        assertTrue(events.none { it is SpeechEvent.Retranscribed })
        answer(after, "가나")
        assertEquals("가나", (events.last() as SpeechEvent.Retranscribed).utterance.text)
    }

    @Test
    fun 確定を出さなかったカードの音声は取り置かない() {
        retention.retain(9, FloatArray(10))
        retention.reset()
        retention.finalEmitted(Utterance(9, "가", "ko", 0, 100, 0))
        retention.retranscribe(9, Language.JA)
        assertEquals(SpeechEvent.RetranscribeFailed(9, RetranscribeFailure.RELEASED), events.last())
    }

    @Test
    fun 指定した言語で書き起こせなかった結果は書き起こせなかったと知らせる() {
        final(1)
        retention.retranscribe(1, Language.KO)
        retention.onResult(jobs.single(), RecognitionOutput.Transcribed(Recognition("あいう", "ja", source = LanguageSource.JUDGED)))
        assertEquals(SpeechEvent.RetranscribeFailed(1, RetranscribeFailure.UNSUPPORTED), events.last())
    }

    @Test
    fun 保持する数が0なら保持せず破棄のイベントも出さない() {
        val none = UtteranceRetention(capacity = 0, emit = { events += it }, submit = { jobs += it as RecognitionJob.Retranscribe })
        none.retain(1, FloatArray(10))
        none.finalEmitted(Utterance(1, "가", "ko", 0, 100, 0))
        assertTrue(events.isEmpty())
        none.retranscribe(1, Language.JA)
        assertEquals(listOf<SpeechEvent>(SpeechEvent.RetranscribeFailed(1, RetranscribeFailure.RELEASED)), events)
    }
}
