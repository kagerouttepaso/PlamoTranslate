package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 確定をカードを開いた順に出す（[CardEvents]）。 */
class CardEventsTest {
    private val out = mutableListOf<SpeechEvent>()
    private val events = CardEvents { out += it }

    private fun finished(id: Long) = SpeechEvent.CardFinished(id, null)

    @Test
    fun `後ろのカードが先に確定しても前のカードの確定の後に出す`() {
        events.opened(0)
        events.opened(1)
        events.opened(2)
        events.finish(2, finished(2))
        events.finish(1, finished(1))
        assertEquals(emptyList<SpeechEvent>(), out)
        assertTrue(events.finished(1))
        assertFalse(events.finished(0))

        events.finish(0, finished(0))
        assertEquals(listOf(finished(0), finished(1), finished(2)), out)
        assertTrue(events.idle)
    }

    @Test
    fun `すべての後に出す出来事は最後のカードの確定の後に出し、カードが無ければすぐ出す`() {
        events.opened(0)
        events.opened(1)
        events.afterAll(SpeechEvent.Flushed)
        events.finish(0, finished(0))
        assertEquals(listOf(finished(0)), out)

        events.finish(1, finished(1))
        assertEquals(listOf(finished(0), finished(1), SpeechEvent.Flushed), out)

        out.clear()
        events.afterAll(SpeechEvent.Flushed)
        assertEquals(listOf(SpeechEvent.Flushed), out)
    }
}
