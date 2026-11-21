package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.Utterance
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackSubtitlesTest {
    @Test
    fun `次の発話の原文と前の発話の訳を組み合わせない`() {
        val session = ConversationSession(PartnerMode.ENGLISH)
        session.handle(Event.Started)
        val first = SpeechEvent.CardFinished(1, Utterance(1, "First sentence.", null, 0, 100, 0))
        val turn = session.handle(Event.Speech(first, Language.EN)).filterIsInstance<Effect.TranslateFinal>().single().turn
        session.handle(Event.FinalDone(turn.id, "最初の文。", 1))

        session.handle(Event.Speech(SpeechEvent.CardOpened(2)))
        assertEquals(SubtitleLine("First sentence.", "最初の文。"), playbackSubtitle(session))
        val second = SpeechEvent.CardText(2, "Second sentence.", null)
        val draft = session.handle(Event.Speech(second, Language.EN)).filterIsInstance<Effect.TranslatePartial>().single()
        assertEquals(SubtitleLine("Second sentence.", ""), playbackSubtitle(session))
        session.handle(Event.PartialDone(draft.requestId, "次の文。"))
        assertEquals(SubtitleLine("Second sentence.", "次の文。"), playbackSubtitle(session))
    }
}
