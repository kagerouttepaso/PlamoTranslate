package app.plamotranslate.pipeline.screen

import app.plamotranslate.pipeline.screen.ScreenSession.Effect
import app.plamotranslate.pipeline.screen.ScreenSession.Phase
import app.plamotranslate.pipeline.screen.ScreenSession.SessionEvent
import app.plamotranslate.pipeline.screen.ScreenSession.Trigger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 画面翻訳の状態遷移（[ScreenSession]）。出来事の並びを与え、状態と作業を確かめる。 */
class ScreenSessionTest {
    /** 出来事を順に入れ、最後の状態と、全部の作業を返す。 */
    private fun run(state: ScreenSession.State, vararg events: SessionEvent): Pair<ScreenSession.State, List<Effect>> {
        var s = state
        val effects = mutableListOf<Effect>()
        for (e in events) {
            val (next, out) = ScreenSession.reduce(s, e)
            s = next
            effects += out
        }
        return s to effects
    }

    private val auto = ScreenSession.initial(Trigger.AUTO)
    private val tap = ScreenSession.initial(Trigger.TAP)

    /** 自動で訳し始め、2件の訳を待っているところまで進める。 */
    private fun translatingAuto() = run(
        auto, SessionEvent.Settled, SessionEvent.Captured(1, ok = true), SessionEvent.Recognized(1, targets = 2),
    ).first

    @Test
    fun 自動で訳すと落ち着いたら撮って読んで訳す() {
        val (s, effects) = run(
            auto, SessionEvent.Settled, SessionEvent.Captured(1, true), SessionEvent.Recognized(1, 2),
            SessionEvent.SegmentDone(1), SessionEvent.SegmentDone(1),
        )
        assertEquals(Phase.Showing(1), s.phase)
        assertEquals(
            listOf(Effect.Capture(1), Effect.BeginValidity(1), Effect.Recognize(1), Effect.Translate(1), Effect.Finished(1)),
            effects,
        )
    }

    @Test
    fun 訳している途中に無効になったら取り消して待ちに戻る() {
        // 2026-11-12の不具合：Discordで訳し始めと同時に戻ったのに、訳し終わった前の画面の訳がチャンネル一覧の上に出た
        val (s, effects) = run(translatingAuto(), SessionEvent.Invalidated("画面が切り替わった"))
        assertEquals(Phase.Watching, s.phase)
        assertEquals(listOf(Effect.Cancel(1), Effect.ClearOverlay, Effect.EndValidity, Effect.ResetStart), effects)
    }

    @Test
    fun 取り消した後に届いた古い結果は捨てる() {
        val (s, effects) = run(
            translatingAuto(), SessionEvent.Invalidated("画面が切り替わった"),
            // 取り消した前のセッションの結果が遅れて届く
            SessionEvent.SegmentDone(1), SessionEvent.SegmentDone(1), SessionEvent.Recognized(1, 3), SessionEvent.Captured(1, true),
        )
        assertEquals(Phase.Watching, s.phase)
        assertTrue(effects.none { it is Effect.Finished || it is Effect.Translate || it is Effect.Recognize })
    }

    @Test
    fun どこから取り消しても後片付けは同じ() {
        val events = listOf(
            SessionEvent.Invalidated("x"), SessionEvent.OutsideTouched, SessionEvent.MenuOpened, SessionEvent.Rotated,
            SessionEvent.ModelChanged, SessionEvent.StatusTapped,
        )
        for (e in events) {
            val (_, effects) = run(translatingAuto(), e)
            assertEquals("$e", listOf(Effect.Cancel(1), Effect.ClearOverlay, Effect.EndValidity, Effect.ResetStart), effects)
        }
    }

    @Test
    fun 自動で訳を出しているときに触れたら枠の下をのぞく() {
        val showing = run(translatingAuto(), SessionEvent.SegmentDone(1), SessionEvent.SegmentDone(1)).first
        val (s, effects) = run(showing, SessionEvent.OutsideTouched)
        assertEquals(Phase.Showing(1), s.phase)
        assertEquals(listOf(Effect.Peek(1)), effects)
    }

    @Test
    fun タップで訳すときはボタンで訳し_もう一度押すと消す() {
        val (s1, e1) = run(tap, SessionEvent.ButtonTapped)
        assertEquals(Phase.Capturing(1), s1.phase)
        assertEquals(listOf(Effect.Capture(1)), e1)
        val showing = run(s1, SessionEvent.Captured(1, true), SessionEvent.Recognized(1, 1), SessionEvent.SegmentDone(1)).first
        val (s2, e2) = run(showing, SessionEvent.ButtonTapped)
        assertEquals(Phase.Idle, s2.phase)
        assertEquals(listOf(Effect.Cancel(1), Effect.ClearOverlay, Effect.EndValidity), e2)
    }

    @Test
    fun 自動のときボタンで止めると落ち着いても訳さない() {
        val (s, effects) = run(auto, SessionEvent.ButtonTapped, SessionEvent.Settled)
        assertEquals(Phase.Idle, s.phase)
        assertTrue(effects.contains(Effect.ButtonActive(false)))
        assertTrue(effects.none { it is Effect.Capture })
        // もう一度押すと待ちに戻る
        val (s2, e2) = run(s, SessionEvent.ButtonTapped)
        assertEquals(Phase.Watching, s2.phase)
        assertTrue(e2.contains(Effect.ResetStart))
    }

    @Test
    fun メニューを開いている間は訳し始めない() {
        val (s, effects) = run(auto, SessionEvent.MenuOpened, SessionEvent.Settled)
        assertEquals(Phase.Watching, s.phase)
        assertTrue(effects.none { it is Effect.Capture })
        val (s2, e2) = run(s, SessionEvent.MenuClosed, SessionEvent.Settled)
        assertEquals(Phase.Capturing(1), s2.phase)
        assertEquals(listOf(Effect.ResetStart, Effect.Capture(1)), e2)
    }

    @Test
    fun メニューを開いたまま自動に変えても閉じるまで訳し始めない() {
        // メニューは選んでも閉じないので、始め方の変更はメニューを開いたまま届く
        val (s, effects) = run(tap, SessionEvent.MenuOpened, SessionEvent.TriggerChanged(Trigger.AUTO), SessionEvent.Settled)
        assertEquals(Phase.Watching, s.phase)
        assertTrue(effects.none { it is Effect.Capture })
        val (s2, e2) = run(s, SessionEvent.MenuClosed, SessionEvent.Settled)
        assertEquals(Phase.Capturing(1), s2.phase)
        assertEquals(listOf(Effect.ResetStart, Effect.Capture(1)), e2)
    }

    @Test
    fun 訳す文字が無ければ知らせて判定Bを続ける() {
        val (s, effects) = run(auto, SessionEvent.Settled, SessionEvent.Captured(1, true), SessionEvent.Recognized(1, 0))
        assertEquals(Phase.Showing(1), s.phase)
        assertEquals(Effect.NothingToTranslate(1), effects.last())
        // 画面が変わったら待ちへ
        assertEquals(Phase.Watching, run(s, SessionEvent.Invalidated("画面が変わった")).first.phase)
    }

    @Test
    fun 撮れなければ知らせて待ちに戻る() {
        val (s, effects) = run(auto, SessionEvent.Settled, SessionEvent.Captured(1, ok = false))
        assertEquals(Phase.Watching, s.phase)
        assertTrue(effects.contains(Effect.Failed(1, "画面を撮影できませんでした")))
    }

    @Test
    fun セッション番号は撮るたびに増える() {
        val s = run(auto, SessionEvent.Settled, SessionEvent.Invalidated("x"), SessionEvent.Settled).first
        assertEquals(Phase.Capturing(2), s.phase)
    }
}
