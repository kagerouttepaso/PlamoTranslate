package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
import org.junit.Test

/** 認識のジョブの待ち行列（[RecognitionQueue]）の約束。 */
class RecognitionQueueTest {
    private val languages = SpeechLanguages(setOf("ja", "ko"))

    private fun transcribe(id: Long, card: Long, priority: RecognitionPriority) =
        RecognitionJob.Transcribe(id, card, priority, FloatArray(0), 0, "試験", TranscriptLanguage.Specified("ja"))

    @Test
    fun 閉じたカードの途中結果と区間の判定だけを捨て確定に要るジョブと書き起こし直しは残す() {
        val q = RecognitionQueue()
        q.submit(transcribe(1, card = 0, RecognitionPriority.PARTIAL))
        q.submit(RecognitionJob.Language(2, 0, RecognitionLane.TRANSCRIBE, RecognitionPriority.LANGUAGE, FloatArray(0), 0, "試験", languages))
        q.submit(transcribe(3, card = 0, RecognitionPriority.FINAL))
        q.submit(RecognitionJob.Retranscribe(4, utterance = 0, FloatArray(0), "試験", Language.KO, 0))
        q.submit(transcribe(5, card = 1, RecognitionPriority.PARTIAL))
        assertEquals(listOf(1L, 2L), q.dropStale(openCard = 1).map { it.id })
        // 処理の順：確定に要るジョブ、書き起こし直し、話し中のカードの途中結果
        assertEquals(listOf(3L, 4L, 5L), generateSequence { q.take(RecognitionLane.TRANSCRIBE) }.map { it.id }.toList())
    }

    @Test
    fun 確定に要るジョブは前のカードのものから出す() {
        // 確定はカードの順に出すので、後ろのカードの確定の残りが前のカードの文を追い越すと、前のカードの確定が待たされる
        val q = RecognitionQueue()
        q.submit(transcribe(1, card = 0, RecognitionPriority.PHRASE))
        q.submit(transcribe(2, card = 1, RecognitionPriority.FINAL))
        q.submit(transcribe(3, card = 0, RecognitionPriority.FINAL))
        q.submit(transcribe(4, card = 1, RecognitionPriority.PARTIAL))
        q.submit(RecognitionJob.Retranscribe(5, utterance = 0, FloatArray(0), "試験", Language.KO, 0))
        // 前のカードの中は優先順位の順、続いて後ろのカード、書き起こし直し、途中結果
        assertEquals(listOf(3L, 1L, 2L, 5L, 4L), generateSequence { q.take(RecognitionLane.TRANSCRIBE) }.map { it.id }.toList())
    }

    @Test
    fun 書き起こし直しは確定に要るジョブに数えない() {
        val q = RecognitionQueue()
        q.submit(RecognitionJob.Retranscribe(1, utterance = 0, FloatArray(0), "試験", Language.KO, 0))
        assertEquals(false, q.hasUrgent())
        assertEquals(null, q.oldestTranscribeEnd())
    }
}
