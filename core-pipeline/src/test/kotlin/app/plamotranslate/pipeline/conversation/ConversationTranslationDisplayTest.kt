package app.plamotranslate.pipeline.conversation

import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConversationTranslationDisplayTest {
    private val session = ConversationSession(PartnerMode.ENGLISH).apply { handle(Event.Started) }
    private val turn get() = session.turns.single()

    private fun speech(event: SpeechEvent) = session.handle(Event.Speech(event))

    private fun openWithText(card: Long, text: String): Long {
        speech(SpeechEvent.CardOpened(card))
        return speech(SpeechEvent.CardText(card, text, "ja")).filterIsInstance<Effect.TranslatePartial>().single().requestId
    }

    private fun finish(card: Long, text: String, lang: String = "ja") {
        speech(SpeechEvent.CardFinished(card, Utterance(card, text, lang, startMs = 0, durationMs = 1000, recognizeMs = 100)))
    }

    @Test
    fun 確定したカードの途中訳は最初の確定訳の文字で置き換わる() {
        val first = openWithText(1, "今日")
        session.handle(Event.PartialDone(first, "Today"))
        speech(SpeechEvent.CardClosed(1))
        val second = openWithText(2, "明日")
        session.handle(Event.PartialDone(second, "Tomorrow"))

        finish(1, "今日は晴れです")
        assertEquals("Today", turn.displayTranslation)
        assertFalse(turn.translated)
        assertEquals("Tomorrow", session.listeningCards.single().translation)
        session.handle(Event.FinalProgress(turn.id, " "))
        assertEquals("Today", turn.displayTranslation)
        session.handle(Event.FinalProgress(turn.id, "It"))
        assertEquals("It", turn.displayTranslation)
        assertFalse(turn.translated)
        session.handle(Event.FinalDone(turn.id, "It is sunny today.", 100))
        assertEquals("It is sunny today.", turn.displayTranslation)
        assertTrue(turn.translated)
    }

    @Test
    fun 途中訳が間に合わなかったカードは確定訳の文字を待つ() {
        val request = openWithText(1, "今日")
        finish(1, "今日は晴れです")
        session.handle(Event.PartialDone(request, "Today"))
        assertEquals("", turn.displayTranslation)
        assertFalse(turn.translated)
        session.handle(Event.FinalProgress(turn.id, "It"))
        assertEquals("It", turn.displayTranslation)
    }

    @Test
    fun 確定訳が直接届いたら途中訳を全文で置き換える() {
        val request = openWithText(1, "今日")
        session.handle(Event.PartialDone(request, "Today"))
        finish(1, "今日は晴れです")
        session.handle(Event.FinalDone(turn.id, "It is sunny today.", 0))
        assertEquals("It is sunny today.", turn.displayTranslation)
        assertTrue(turn.translated)
    }

    @Test
    fun 確定訳が失敗したら途中訳を取り下げて失敗の理由を残す() {
        val request = openWithText(1, "今日")
        session.handle(Event.PartialDone(request, "Today"))
        finish(1, "今日は晴れです")
        session.handle(Event.FinalDone(turn.id, "", 0, failure = "中断"))
        assertEquals("", turn.displayTranslation)
        assertEquals("中断", turn.failure)
        assertTrue(turn.translated)
    }

    @Test
    fun 確定訳の生成中に失敗したら生成済みの文字と失敗の理由が残る() {
        val request = openWithText(1, "今日")
        session.handle(Event.PartialDone(request, "Today"))
        finish(1, "今日は晴れです")
        session.handle(Event.FinalProgress(turn.id, "It"))
        session.handle(Event.FinalDone(turn.id, "", 0, failure = "中断"))
        assertEquals("It", turn.displayTranslation)
        assertEquals("中断", turn.failure)
    }

    @Test
    fun 確定時に訳す方向が変わったら前の方向の途中訳を引き継がない() {
        val request = openWithText(1, "一")
        session.handle(Event.PartialDone(request, "One"))
        finish(1, "One", "en")
        assertEquals("", turn.displayTranslation)
    }

    @Test
    fun 言語の判定が変わってから届いた途中訳を確定カードに引き継がない() {
        val request = openWithText(1, "一")
        speech(SpeechEvent.CardText(1, "One", "en"))
        session.handle(Event.PartialDone(request, "One"))
        finish(1, "One", "en")
        assertEquals("", turn.displayTranslation)
    }
}
