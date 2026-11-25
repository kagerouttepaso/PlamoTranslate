package app.plamotranslate.pipeline.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.speech.RetranscribeFailure
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.Utterance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 会話の訳す順番（[ConversationSession]）。 */
class ConversationSessionTest {
    @Test
    fun 準備と入力の確認が済むと一度だけ始め停止後は開始操作を待つ() {
        val session = ConversationSession().apply { handle(Event.StartRequested) }
        session.handle(Event.ModelsChanged(true))
        assertTrue(session.awaitingInput)
        assertEquals(listOf(Effect.Begin), session.handle(Event.InputAvailable))
        assertTrue(session.handle(Event.ModelsChanged(true)).isEmpty())
        assertTrue(session.handle(Event.InputAvailable).isEmpty())
        assertEquals(listOf(Effect.Flush), session.handle(Event.StopRequested))
        assertEquals(listOf(Effect.Finish), session.handle(Event.Speech(SpeechEvent.Flushed)))
        session.handle(Event.ModelsChanged(true))
        assertFalse(session.awaitingInput)
        assertTrue(session.handle(Event.InputAvailable).isEmpty())
        session.handle(Event.StartRequested)
        assertEquals(listOf(Effect.Begin), session.handle(Event.InputAvailable))
        assertEquals(listOf(Effect.Finish), session.handle(Event.Closed))
    }

    @Test
    fun 準備中や権限待ちに停止するか画面を閉じたら遅れて準備が済んでも始めない() {
        for (waitingForInput in listOf(false, true)) {
            for (cancel in listOf(Event.StopRequested, Event.Closed)) {
                val session = ConversationSession().apply { handle(Event.StartRequested) }
                if (waitingForInput) session.handle(Event.ModelsChanged(true))
                assertTrue(session.handle(cancel).isEmpty())
                session.handle(Event.ModelsChanged(true))
                assertFalse(session.awaitingInput)
                assertTrue(session.handle(Event.InputAvailable).isEmpty())
                assertFalse(session.listening)
            }
        }
    }

    @Test
    fun 入力の確認中に準備を失ったら待ち直し聞き始めた後の読み直しでは録音を続ける() {
        val session = ConversationSession()
        session.handle(Event.StartRequested)
        assertFalse(session.awaitingInput)
        session.handle(Event.ModelsChanged(true))
        session.handle(Event.ModelsChanged(false))
        assertTrue(session.handle(Event.InputAvailable).isEmpty())
        session.handle(Event.ModelsChanged(true))
        assertEquals(listOf(Effect.Begin), session.handle(Event.InputAvailable))
        assertTrue(session.handle(Event.ModelsChanged(false)).isEmpty())
        assertTrue(session.listening)
        session.handle(Event.StopRequested)
        session.handle(Event.Speech(SpeechEvent.Flushed))
        assertFalse(session.canStart)
        session.handle(Event.ModelsChanged(true))
        assertTrue(session.canStart)
        assertFalse(session.awaitingInput)
    }

    /** 音声認識のカードの番号の採番と、話し中のカード。 */
    private var nextCard = 100L
    private var card = -1L

    /** カード [id] の確定。番号を省くと、まだ使っていない番号のカード（前に話した分）の確定。 */
    private fun final(text: String, lang: String = "ja", id: Long = nextCard++) =
        Event.Speech(SpeechEvent.CardFinished(id, Utterance(id, text, lang, startMs = 0, durationMs = 1000, recognizeMs = 100)))

    /** 話し中のカードの途中の文字。 */
    private fun partial(text: String, lang: String = "ja") = Event.Speech(SpeechEvent.CardText(card, text, lang))

    /** 聞いていなければ聞き始め、話し始める（新しいカードを開く）。 */
    private fun ConversationSession.speak(): List<Effect> {
        handle(Event.Started)
        card = nextCard++
        return handle(Event.Speech(SpeechEvent.CardOpened(card)))
    }

    /** 話し中のカードを閉じる（話し終わり・区切る）。 */
    private fun closed(id: Long = card) = Event.Speech(SpeechEvent.CardClosed(id))

    /** 話し中のカード。 */
    private val ConversationSession.live: LiveSpeech? get() = listeningCards.lastOrNull { it.open }

    @Test
    fun 途中と確定は認識器の言語より指定した発話者の言語を使う() {
        val session = ConversationSession(PartnerMode.ENGLISH)
        session.speak()
        val draft = session.handle(partial("123").copy(specifiedLanguage = Language.EN))
            .filterIsInstance<Effect.TranslatePartial>().single()
        assertEquals(Language.EN, draft.source)
        assertEquals(Language.JA, draft.target)
        val done = session.handle(final("123", id = card).copy(specifiedLanguage = Language.EN))
            .filterIsInstance<Effect.TranslateFinal>().single()
        assertEquals(Direction(Language.EN, Language.JA, mine = false), done.turn.direction)
    }

    @Test
    fun 自動で相手が未定なら指定された言語で相手を確定する() {
        val session = ConversationSession()
        session.speak()
        session.handle(partial("123").copy(specifiedLanguage = Language.EN))
        assertFalse(session.partnerFound)
        session.handle(final("123", id = card).copy(specifiedLanguage = Language.EN))
        assertTrue(session.partnerFound)
        assertEquals(Language.EN, session.partner)
    }

    @Test
    fun 確定訳は話した順に1件ずつ渡す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        val first = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>()
        assertEquals(listOf("こんにちは"), first.map { it.turn.original })
        assertTrue(s.handle(final("元気です")).none { it is Effect.TranslateFinal })
        val next = s.handle(Event.FinalDone(first.single().turn.id, "안녕하세요", 300))
        assertEquals(listOf("元気です"), next.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
        assertEquals("안녕하세요", s.turns[0].translation)
        assertTrue(s.turns[0].translated)
    }

    @Test
    fun 文字の無い途中結果はカードの向きだけ決めて訳さない() {
        var now = 1_000L
        val s = ConversationSession(PartnerMode.KOREAN, clock = { now })
        s.speak()
        now = 2_000L
        assertTrue(s.handle(partial("", lang = "ko")).none { it is Effect.TranslatePartial })
        val live = s.live!!
        assertEquals("", live.text)
        assertEquals(Language.KO, live.direction?.source)
        // 話し始めの時刻は、言語のイベントでは変えない（画面は話し始めからの秒数を出す）
        assertEquals(1_000L, live.startedAtMs)
    }

    @Test
    fun 前のカードを書き起こしている間に話し始めてもその場で話し中になる() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val first = card
        s.handle(partial("こんにちは"))
        s.handle(closed())
        // 前のカードの確定を待たずに、次のカードが話し中になる
        s.speak()
        assertEquals(listOf(first to false, card to true), s.listeningCards.map { it.card to it.open })
        assertEquals("こんにちは", s.listeningCards.first().text)
        s.handle(partial("元気"))
        assertEquals("元気", s.live?.text)
        // 前のカードが確定すると、そのカードだけが確定したカードに置き換わる
        s.handle(final("こんにちは", id = first))
        assertEquals(listOf("こんにちは"), s.turns.map { it.original })
        assertEquals(listOf(card), s.listeningCards.map { it.card })
    }

    @Test
    fun 続けて2回区切ると1枚ずつ確定に置き換わる() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val first = card
        s.handle(partial("一"))
        s.handle(closed())
        s.speak()
        val second = card
        s.handle(partial("二"))
        s.handle(closed())
        assertEquals(listOf("一", "二"), s.listeningCards.map { it.text })
        s.handle(final("一", id = first))
        assertEquals(listOf("二"), s.listeningCards.map { it.text })
        s.handle(final("二", id = second))
        assertTrue(s.listeningCards.isEmpty())
        assertEquals(listOf("一", "二"), s.turns.map { it.original })
    }

    @Test
    fun 何も聞き取れなかったカードは残らない() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        s.handle(closed())
        s.handle(Event.Speech(SpeechEvent.CardFinished(card, null)))
        assertTrue(s.listeningCards.isEmpty())
        assertTrue(s.turns.isEmpty())
    }

    @Test
    fun 区切るボタンは話し中のカードがあるときだけ押せる() {
        val s = ConversationSession(PartnerMode.KOREAN)
        assertFalse(s.canCut)
        s.handle(Event.Started)
        // 聞いていても、話していなければ押せない
        assertFalse(s.canCut)
        s.speak()
        assertTrue(s.canCut)
        // 区切る・話し終わり（どちらもカードを閉じる）で押せなくなり、書き起こし待ちの間も押せない
        s.handle(closed())
        assertFalse(s.canCut)
        s.speak()
        assertTrue(s.canCut)
        // 止めると押せない
        s.handle(Event.StopRequested)
        assertFalse(s.canCut)
    }

    @Test
    fun 止めたら確定し終えるまで止まらず確定し終えたら止まる() {
        val s = ConversationSession(PartnerMode.KOREAN)
        assertEquals(listOf(Effect.Begin), s.handle(Event.Started))
        s.speak()
        s.handle(partial("こんに"))
        assertEquals(listOf(Effect.Flush), s.handle(Event.StopRequested))
        assertTrue(s.listening)
        // 止めている間に、もう一度止めても始めても何もしない
        assertTrue(s.handle(Event.StopRequested).isEmpty())
        assertTrue(s.handle(Event.Started).isEmpty())
        // 止めた後に届いた途中の文字は出さず、訳さない
        assertTrue(s.handle(partial("こんにちは")).none { it is Effect.TranslatePartial })
        assertEquals("こんに", s.listeningCards.single().text)
        // 話していた分の確定は訳す
        s.handle(closed())
        assertTrue(s.handle(final("こんにちは", id = card)).any { it is Effect.TranslateFinal })
        assertTrue(s.listening)
        assertTrue(s.handle(Event.Speech(SpeechEvent.Flushed)).contains(Effect.Finish))
        assertFalse(s.listening)
        assertTrue(s.listeningCards.isEmpty())
        // 止め終わってから止めても、届いた止め終わりも、何もしない
        assertTrue(s.handle(Event.StopRequested).isEmpty())
        assertTrue(s.handle(Event.Speech(SpeechEvent.Flushed)).isEmpty())
    }

    @Test
    fun 止める前に渡した音声のカードは止めた後に開いても確定まで出す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.handle(Event.Started)
        s.handle(Event.StopRequested)
        s.speak()
        assertEquals(listOf(card), s.listeningCards.map { it.card })
        assertTrue(s.handle(final("はい", id = card)).any { it is Effect.TranslateFinal })
        assertTrue(s.listeningCards.isEmpty())
    }

    @Test
    fun 画面を閉じたら確定を待たずに聞き終える() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        s.handle(Event.StopRequested)
        // 聞き始めと聞き終えは1回ずつ対
        assertEquals(listOf(Effect.Finish), s.handle(Event.Closed))
        assertFalse(s.listening)
        assertTrue(s.listeningCards.isEmpty())
        assertTrue(s.handle(Event.Closed).isEmpty())
        assertTrue(s.handle(Event.Speech(SpeechEvent.Flushed)).isEmpty())
    }

    @Test
    fun 音声認識が確定を書き起こしている間は途中訳を待たせ空いたら最新を訳す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        s.handle(Event.RecognitionBusy(true))
        assertTrue(s.handle(partial("こんに")).none { it is Effect.TranslatePartial })
        assertTrue(s.handle(partial("こんにちは")).none { it is Effect.TranslatePartial })
        val started = s.handle(Event.RecognitionBusy(false)).filterIsInstance<Effect.TranslatePartial>()
        assertEquals(listOf("こんにちは"), started.map { it.text })
    }

    @Test
    fun 確定訳の途中はカードに出す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalProgress(id, "안녕"))
        assertEquals("안녕", s.turns.single().translation)
        assertTrue(!s.turns.single().translated)
    }

    @Test
    fun 確定が来たら走っている途中訳を取り消す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val request = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        val effects = s.handle(final("こんにちは"))
        assertTrue(effects.contains(Effect.CancelPartial(request.requestId)))
        assertTrue(effects.any { it is Effect.TranslateFinal })
    }

    @Test
    fun 確定訳が残っている間は途中訳を始めない() {
        val s = ConversationSession(PartnerMode.KOREAN)
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.speak()
        assertTrue(s.handle(partial("元気")).none { it is Effect.TranslatePartial })
        // 確定訳が終わっても、捨てた途中の文字は訳さない（次の途中の文字を待つ）
        assertTrue(s.handle(Event.FinalDone(id, "안녕하세요", 300)).none { it is Effect.TranslatePartial })
        assertEquals(1, s.handle(partial("元気です")).filterIsInstance<Effect.TranslatePartial>().size)
    }

    @Test
    fun 途中訳の間に届いた途中の文字は最新のものだけを次に訳す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val first = s.handle(partial("こん")).filterIsInstance<Effect.TranslatePartial>().single()
        assertTrue(s.handle(partial("こんに")).isEmpty())
        assertTrue(s.handle(partial("こんにち")).isEmpty())
        val next = s.handle(Event.PartialDone(first.requestId, "안")).filterIsInstance<Effect.TranslatePartial>()
        assertEquals(listOf("こんにち"), next.map { it.text })
        assertEquals("안", s.live?.translation)
    }

    @Test
    fun 古い発話の途中訳は出さない() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val request = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        // 話し終わって次の発話が始まった
        s.handle(closed())
        s.speak()
        s.handle(Event.PartialDone(request.requestId, "안녕"))
        assertEquals("", s.live?.translation)
    }

    @Test
    fun 新しい途中の文字を訳し終わるまで前の途中訳を出しておく() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
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
    fun 相手が分かる前の自分の発話は原文だけを出し相手が決まったらさかのぼって訳す() {
        val s = ConversationSession(self = Language.JA)
        // 相手不明：訳さず、途中訳もしない
        assertTrue(s.handle(final("こんにちは")).none { it is Effect.TranslateFinal })
        s.speak()
        assertTrue(s.handle(partial("元気")).none { it is Effect.TranslatePartial })
        assertEquals(null, s.turns.single().direction.target)
        // 相手が英語で話した：さかのぼって自分の発話を英語へ訳し、相手の発話を訳す（話した順に）
        val first = s.handle(final("Hello", "en")).filterIsInstance<Effect.TranslateFinal>().single()
        assertEquals("こんにちは", first.turn.original)
        assertEquals(Language.EN, s.turns[0].direction.target)
        val next = s.handle(Event.FinalDone(first.turn.id, "Hi", 100)).filterIsInstance<Effect.TranslateFinal>()
        assertEquals(listOf("Hello"), next.map { it.turn.original })
    }

    @Test
    fun 相手の言語を選び直したら自分の発話を新しい相手の言語へ訳し直す() {
        val s = ConversationSession(PartnerMode.ENGLISH)
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalDone(id, "Hello", 100))
        val again = s.handle(Event.ModeChanged(PartnerMode.KOREAN)).filterIsInstance<Effect.TranslateFinal>().single()
        assertEquals(Direction(Language.JA, Language.KO, mine = true), again.turn.direction)
        assertEquals("", s.turns.single().translation)
        assertTrue(!s.turns.single().translated)
    }

    @Test
    fun 自動を選び直して相手が見つかっても訳した自分の発話は訳し直さない() {
        val s = ConversationSession(PartnerMode.ENGLISH)
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalDone(id, "Hello", 100))
        s.handle(Event.ModeChanged(PartnerMode.AUTO))
        // 自動で相手が韓国語と分かる：訳すのは相手の発話だけ
        val next = s.handle(final("안녕하세요", "ko")).filterIsInstance<Effect.TranslateFinal>()
        assertEquals(listOf("안녕하세요"), next.map { it.turn.original })
        assertEquals("Hello", s.turns[0].translation)
        assertEquals(Language.EN, s.turns[0].direction.target)
    }

    @Test
    fun 訳している最中に相手を選び直したら古い言語の訳を発話に書かない() {
        val s = ConversationSession(PartnerMode.ENGLISH)
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        // 英語へ訳している途中で韓国語を選ぶ：走っている訳が終わるまで次は渡さない
        assertTrue(s.handle(Event.ModeChanged(PartnerMode.KOREAN)).none { it is Effect.TranslateFinal })
        s.handle(Event.FinalProgress(id, "Hel"))
        assertEquals("", s.turns.single().translation)
        val again = s.handle(Event.FinalDone(id, "Hello", 100)).filterIsInstance<Effect.TranslateFinal>().single()
        assertEquals(Language.KO, again.turn.direction.target)
        assertEquals("", s.turns.single().translation)
        assertTrue(!s.turns.single().translated)
        s.handle(Event.FinalDone(id, "안녕하세요", 100))
        assertEquals("안녕하세요", s.turns.single().translation)
    }

    @Test
    fun 消去したらまだ渡していない訳も捨てる() {
        val s = ConversationSession(PartnerMode.KOREAN)
        val id = s.handle(final("一")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(final("二"))
        s.handle(Event.Cleared)
        assertTrue(s.handle(Event.FinalDone(id, "하나", 100)).none { it is Effect.TranslateFinal })
    }

    @Test
    fun 消去で発話を消す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.handle(final("こんにちは"))
        s.handle(Event.Cleared)
        assertTrue(s.turns.isEmpty())
    }

    @Test
    fun 確定訳が失敗したら理由を持って次へ進む() {
        val s = ConversationSession(PartnerMode.KOREAN)
        val id = s.handle(final("一")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(final("二"))
        val next = s.handle(Event.FinalDone(id, "", 0, failure = "エンジンが無い"))
        assertEquals("エンジンが無い", s.turns[0].failure)
        assertTrue(s.turns[0].translated)
        assertEquals(listOf("二"), next.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
    }

    @Test
    fun 取り消した途中訳が終わったら次の途中の文字を訳す() {
        val s = ConversationSession(PartnerMode.KOREAN)
        s.speak()
        val r = s.handle(partial("こんに")).filterIsInstance<Effect.TranslatePartial>().single()
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.PartialDone(r.requestId, null))
        s.handle(Event.FinalDone(id, "안녕하세요", 300))
        assertEquals(listOf("元気"), s.handle(partial("元気")).filterIsInstance<Effect.TranslatePartial>().map { it.text })
    }

    @Test
    fun カードは発話の時刻とかかった時間を数値で持つ() {
        val s = ConversationSession(PartnerMode.KOREAN, clock = { 1_000L })
        val id = s.handle(final("こんにちは")).filterIsInstance<Effect.TranslateFinal>().single().turn.id
        s.handle(Event.FinalDone(id, "안녕하세요", 300))
        val t = s.turns.single()
        assertEquals(1_000L, t.timeMillis)
        assertEquals(1000L, t.durationMs)
        assertEquals(100L, t.recognizeMs)
        assertEquals(300L, t.translateMs)
    }

    @Test
    fun カードの言語を入れ替えたら先に話し手を替え書き起こし直しが届いたら原文を差し替えて訳す() {
        val s = ConversationSession(PartnerMode(Language.KO))
        // 韓国語の話を、音声認識が日本語（自分）の発話と取り違えた
        val first = s.handle(final("가나다", lang = "ja", id = 7)).filterIsInstance<Effect.TranslateFinal>().single()
        s.handle(Event.FinalDone(first.turn.id, "가나다", 100))
        val turn = s.turns.single()
        assertEquals(SwapState.AVAILABLE, turn.swap)

        val asked = s.handle(Event.LanguageSwapped(turn.id))
        assertEquals(listOf(Effect.Retranscribe(7, Language.KO)), asked)
        // 向き（話し手）はその場で替わり、訳は空になる。原文は届くまで前のまま
        val swapping = s.turns.single()
        assertEquals(Direction(Language.KO, Language.JA, mine = false), swapping.direction)
        assertEquals("가나다", swapping.original)
        assertEquals("", swapping.translation)
        assertEquals(turn, swapping.swappedFrom)
        // 書き起こし直している間は入れ替えられない（もう一度スライドしても頼み直さない）
        assertEquals(SwapState.REWRITING, swapping.swap)
        assertTrue(s.handle(Event.LanguageSwapped(turn.id)).isEmpty())

        val redo = s.handle(Event.Speech(SpeechEvent.Retranscribed(7, Utterance(7, "가나다라", "ko", 0, 1000, 300))))
        val redone = s.turns.single()
        assertEquals("가나다라", redone.original)
        assertEquals(Direction(Language.KO, Language.JA, mine = false), redone.direction)
        assertEquals(null, redone.swappedFrom)
        assertEquals(SwapState.AVAILABLE, redone.swap)
        assertEquals(listOf("가나다라"), redo.filterIsInstance<Effect.TranslateFinal>().map { it.turn.original })
    }

    @Test
    fun 書き起こし直せなかったら入れ替える前のカードに戻る() {
        val s = ConversationSession(PartnerMode(Language.KO))
        val first = s.handle(final("안녕하세요", lang = "ja", id = 3)).filterIsInstance<Effect.TranslateFinal>().single()
        s.handle(Event.FinalDone(first.turn.id, "안녕하세요", 100))
        val turn = s.turns.single()
        s.handle(Event.LanguageSwapped(turn.id))
        s.handle(Event.Speech(SpeechEvent.RetranscribeFailed(3, RetranscribeFailure.NOTHING_HEARD)))
        assertEquals(turn, s.turns.single())
    }

    @Test
    fun 訳している最中に入れ替えたらその訳は書かない() {
        val s = ConversationSession(PartnerMode(Language.KO))
        val first = s.handle(final("가나다", lang = "ja", id = 1)).filterIsInstance<Effect.TranslateFinal>().single()
        s.handle(Event.LanguageSwapped(first.turn.id))
        s.handle(Event.FinalProgress(first.turn.id, "古い訳"))
        s.handle(Event.FinalDone(first.turn.id, "古い訳", 100))
        assertEquals("", s.turns.single().translation)
        val redo = s.handle(Event.Speech(SpeechEvent.Retranscribed(1, Utterance(1, "가나다", "ko", 0, 1000, 300))))
        assertEquals(listOf(Language.KO), redo.filterIsInstance<Effect.TranslateFinal>().map { it.turn.direction.source })
    }

    @Test
    fun 音声を手放した発話と相手の決まっていない自分の発話は入れ替えられない() {
        val s = ConversationSession(PartnerMode(Language.KO))
        s.handle(final("こんにちは", id = 1))
        s.handle(Event.Speech(SpeechEvent.Released(1)))
        assertEquals(SwapState.EXPIRED, s.turns.single().swap)
        assertTrue(s.handle(Event.LanguageSwapped(s.turns.single().id)).isEmpty())

        val auto = ConversationSession(PartnerMode.AUTO)
        auto.handle(final("こんにちは", id = 2))
        assertEquals(SwapState.UNAVAILABLE, auto.turns.single().swap)
        assertTrue(auto.handle(Event.LanguageSwapped(auto.turns.single().id)).isEmpty())
        // 相手が決まったら入れ替えられる
        auto.handle(final("안녕하세요", lang = "ko", id = 3))
        assertEquals(SwapState.AVAILABLE, auto.turns.first().swap)
    }

    @Test
    fun 書き起こし直しを待っている間に相手を選び直しても前の原文は訳さない() {
        val s = ConversationSession(PartnerMode(Language.KO))
        // 相手の発話（韓国語と判定）を、自分の発話（日本語）に入れ替えた
        val first = s.handle(final("こんにちは", lang = "ko", id = 1)).filterIsInstance<Effect.TranslateFinal>().single()
        s.handle(Event.FinalDone(first.turn.id, "こんにちは", 100))
        s.handle(Event.LanguageSwapped(first.turn.id))
        assertTrue(s.turns.single().direction.mine)
        // 待っている間に相手の言語を選び直す（自分の発話は新しい相手へ訳し直す対象）
        val changed = s.handle(Event.ModeChanged(PartnerMode(Language.EN)))
        assertTrue(changed.filterIsInstance<Effect.TranslateFinal>().isEmpty())
        // 書き起こし直しが届いたら、今の相手の言語へ訳す
        val redo = s.handle(Event.Speech(SpeechEvent.Retranscribed(1, Utterance(1, "こんにちは。", "ja", 0, 1000, 300))))
        assertEquals(listOf(Language.EN), redo.filterIsInstance<Effect.TranslateFinal>().map { it.target })
    }

    @Test
    fun 書き起こし直せずに戻したカードは今の相手の言語へ訳す() {
        val s = ConversationSession(PartnerMode(Language.KO))
        val first = s.handle(final("こんにちは", id = 1)).filterIsInstance<Effect.TranslateFinal>().single()
        s.handle(Event.FinalDone(first.turn.id, "안녕하세요", 100))
        s.handle(Event.LanguageSwapped(first.turn.id))
        s.handle(Event.ModeChanged(PartnerMode(Language.EN)))
        val back = s.handle(Event.Speech(SpeechEvent.RetranscribeFailed(1, RetranscribeFailure.NOTHING_HEARD)))
        val turn = s.turns.single()
        assertEquals(Direction(Language.JA, Language.EN, mine = true), turn.direction)
        assertEquals(listOf(Language.EN), back.filterIsInstance<Effect.TranslateFinal>().map { it.target })
    }
}
