package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** カードをまたぐ話し手（[SpeakerHandoff]）。 */
class SpeakerHandoffTest {
    private val handoff = SpeakerHandoff()
    private val languages = SpeechLanguages(groups = setOf("ja", "ko"), primary = "ja")

    @Test
    fun `決められない音声はカードの言語、前の話し手、自分の言語の順に使う`() {
        assertEquals("ja", handoff.fallback(null, languages))
        handoff.ended("ko")
        assertEquals("ko", handoff.fallback(null, languages))
        assertEquals("ja", handoff.fallback("ja", languages))
        // 話し手の決まらないカードが終わっても、前の話し手は残る
        handoff.ended(null)
        assertEquals("ko", handoff.fallback(null, languages))
        handoff.reset()
        assertEquals("ja", handoff.fallback(null, languages))
    }

    @Test
    fun `話し手の決まらないカードは5秒話してから前の話し手を仮に使う`() {
        handoff.ended("ko")
        val track = SpeakerTrack(start = 0, minTurn = SAMPLE_RATE.toLong())
        assertNull(handoff.provisional(track, SAMPLE_RATE * 5 - 1, languages))
        assertEquals("ko", handoff.provisional(track, SAMPLE_RATE * 5, languages))
        track.assign("ja", sure = false)
        assertNull(handoff.provisional(track, SAMPLE_RATE * 6, languages))
    }
}
