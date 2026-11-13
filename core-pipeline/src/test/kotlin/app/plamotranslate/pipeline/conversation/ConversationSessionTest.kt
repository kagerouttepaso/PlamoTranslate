package app.plamotranslate.pipeline.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会話の訳す順番（[ConversationSession]）。 */
class ConversationSessionTest {
    private fun final(text: String, lang: String = "ja") =
        Event.Speech(SpeechEvent.Final(Utterance(text, lang, startMs = 0, durationMs = 1000, recognizeMs = 100)))

    private fun partial(text: String, lang: String = "ja") = Event.Speech(SpeechEvent.Partial(text, lang))

    private fun speaking(on: Boolean) = Event.Speech(SpeechEvent.Speaking(on))

    @Test
    fun 確定訳は話した順に1件ずつ渡す() {
        val s = ConversationSession()
        val first = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>()
        assertEquals(listOf("こんにちは"), first.map { it.turn.original })
        assertTrue(s.handle(final("元気です")).none { it is Effect.TranslateFinal })
        val next = s.handle(Event.FinalDone(first.single().turn.id, "안녕하세요", 300))
        assertEquals(listOf("元気です"), next.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
        assertEquals("안녕하세요", s.turns[0].translation)
        assertTrue(s.turns[0].translated)
    }

    @Test
    fun 確定訳の途中はカードに出す() {
        val s = ConversationSession()
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalProgress(id, "안녕"))
        assertEquals("안녕", s.turns.single().translation)
        assertTrue(!s.turns.single().translated)
    }

    @Test
    fun 確定が来たら走っている途中訳を取り消す() {
        val s = ConversationSession()
        s.handle(speaking(true))
        val request = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        val effects = s.handle(final("こんにちは"))
        assertTrue(effects.contains(Effect.CancelPartial(request.requestId)))
        assertTrue(effects.any { it is Effect.TranslateFinal })
    }

    @Test
    fun 確定訳が残っている間は途中訳を始めない() {
        val s = ConversationSession()
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        assertTrue(s.handle(partial("元気")).none { it is Effect.TranslatePartial })
        // 確定訳が終わっても、捨てた途中の文字は訳さない（次の途中の文字を待つ）
        assertTrue(s.handle(Event.FinalDone(id, "안녕하세요", 300)).none { it is Effect.TranslatePartial })
        assertEquals(1, s.handle(partial("元気です")).filterIsInstance<Effect.TranslatePartial>().size)
    }

    @Test
    fun 途中訳の間に届いた途中の文字は最新のものだけを次に訳す() {
        val s = ConversationSession()
        s.handle(speaking(true))
        val first = s.handle(partial("こん")).filterIsInstance<Effect.TranslatePartial>().single()
        assertTrue(s.handle(partial("こんに")).isEmpty())
        assertTrue(s.handle(partial("こんにち")).isEmpty())
        val next = s.handle(Event.PartialDone(first.requestId, "안")).filterIsInstance<Effect.TranslatePartial>()
        assertEquals(listOf("こんにち"), next.map { it.text })
        assertEquals("안", s.live?.translation)
    }

    @Test
    fun 古い発話の途中訳は出さない() {
        val s = ConversationSession()
        s.handle(speaking(true))
        val request = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        // 話し終わって次の発話が始まった
        s.handle(speaking(false))
        s.handle(speaking(true))
        s.handle(Event.PartialDone(request.requestId, "안녕"))
        assertEquals("", s.live?.translation)
    }

    @Test
    fun 新しい途中の文字を訳し終わるまで前の途中訳を出しておく() {
        val s = ConversationSession()
        s.handle(speaking(true))
        val r = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        s.handle(Event.PartialDone(r.requestId, "안녕"))
        s.handle(partial("こんにちは"))
        assertEquals("안녕", s.live?.translation)
        assertEquals("こんにちは", s.live?.text)
    }

    @Test
    fun 相手の発話で相手の言語を覚え自分の発話をその言語へ訳す() {
        val s = ConversationSession(self = Language.JA)
        s.handle(final("Hello", "en"))
        assertEquals(Language.EN, s.partner)
        assertEquals(Direction(Language.EN, Language.JA, mine = false), s.turns.last().direction)
    }

    @Test
    fun 止めたら聞き取り中の表示を消し消去で発話を消す() {
        val s = ConversationSession()
        s.handle(speaking(true))
        s.handle(Event.Stopped)
        assertNull(s.live)
        s.handle(final("こんにちは"))
        s.handle(Event.Cleared)
        assertTrue(s.turns.isEmpty())
    }

    @Test
    fun 確定訳が失敗したら理由を持って次へ進む() {
        val s = ConversationSession()
        val id = s.handle(final("一")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(final("二"))
        val next = s.handle(Event.FinalDone(id, "", 0, failure = "エンジンが無い"))
        assertEquals("エンジンが無い", s.turns[0].failure)
        assertTrue(s.turns[0].translated)
        assertEquals(listOf("二"), next.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
    }

    @Test
    fun 取り消した途中訳が終わったら次の途中の文字を訳す() {
        val s = ConversationSession()
        s.handle(speaking(true))
        val r = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.PartialDone(r.requestId, null))
        s.handle(Event.FinalDone(id, "안녕하세요", 300))
        assertEquals(listOf("元気"), s.handle(partial("元気")).filterIsInstance<Effect.TranslatePartial>().map { it.text })
    }

    @Test
    fun カードは発話の時刻とかかった時間を数値で持つ() {
        val s = ConversationSession(clock = { 1_000L })
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalDone(id, "안녕하세요", 300))
        val t = s.turns.single()
        assertEquals(1_000L, t.timeMillis)
        assertEquals(1000L, t.durationMs)
        assertEquals(100L, t.recognizeMs)
        assertEquals(300L, t.translateMs)
    }

    @Test
    fun 消しても待っている確定訳は訳し続ける() {
        // 今の振る舞いを固定する（消したカードの訳は出す先が無いが、確定訳の順番は崩さない）
        val s = ConversationSession()
        val id = s.handle(final("一")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(final("二"))
        s.handle(Event.Cleared)
        val next = s.handle(Event.FinalDone(id, "하나", 100))
        assertEquals(listOf("二"), next.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
    }
}
