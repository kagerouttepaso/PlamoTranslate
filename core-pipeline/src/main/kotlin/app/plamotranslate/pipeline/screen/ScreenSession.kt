package app.plamotranslate.pipeline.screen

/**
 * 画面翻訳の状態遷移（docs/13_画面翻訳の構成.md「状態遷移」）。画面翻訳の制御は、ここだけが決める。
 *
 * 出来事（[SessionEvent]）を受け取り、次の状態と行う作業（[Effect]）を返す純粋な関数（[reduce]）。撮影・文字認識・翻訳・表示は
 * 自分では行わず、作業として返す。作業の結果は、セッション番号つきの出来事として戻ってくる。今のセッションでない結果は捨てる
 * ので、取り消した後に遅れて届いた結果が次の画面に出ることは起きない。
 *
 * 取り消しは「後片付けをして待ちに戻る」遷移（[rest]）の1つだけで、どこから来ても同じ作業（翻訳などの取り消し・訳の枠を消す・判定Bをやめる）を返す。
 */
object ScreenSession {
    /** 翻訳の始め方。 */
    enum class Trigger { TAP, AUTO }

    /** 今していること。 */
    sealed interface Phase {
        /** タップを待つ（自動で訳すのを止めているときも）。 */
        data object Idle : Phase

        /** 自動で訳す：画面が落ち着くのを待つ（判定A）。 */
        data object Watching : Phase

        data class Capturing(val session: Int) : Phase
        data class Recognizing(val session: Int) : Phase
        data class Translating(val session: Int, val total: Int, val done: Int) : Phase

        /** 訳を出している（訳す文字が無かったときは、上部の知らせだけを出している）。 */
        data class Showing(val session: Int) : Phase
    }

    data class State(
        val trigger: Trigger,
        /** 自動で訳すのを動かしているか（浮遊ボタンで止められる）。 */
        val autoOn: Boolean,
        val phase: Phase,
        val menuOpen: Boolean = false,
        val nextSession: Int = 1,
    ) {
        /** 今のセッション番号。撮影から表示までの間だけある。 */
        val session: Int?
            get() = when (val p = phase) {
                is Phase.Capturing -> p.session
                is Phase.Recognizing -> p.session
                is Phase.Translating -> p.session
                is Phase.Showing -> p.session
                else -> null
            }

        /** 撮ってから訳し終わるまで（訳を出しているときは含まない）。 */
        val working: Boolean get() = phase is Phase.Capturing || phase is Phase.Recognizing || phase is Phase.Translating
    }

    fun initial(trigger: Trigger): State =
        State(trigger, autoOn = trigger == Trigger.AUTO, phase = if (trigger == Trigger.AUTO) Phase.Watching else Phase.Idle)

    /** 起きたこと。 */
    sealed interface SessionEvent {
        /** 浮遊ボタンのタップ。 */
        data object ButtonTapped : SessionEvent
        data class TriggerChanged(val trigger: Trigger) : SessionEvent

        /** 判定A：画面が落ち着いた。 */
        data object Settled : SessionEvent
        data class Captured(val session: Int, val ok: Boolean) : SessionEvent
        data class Recognized(val session: Int, val targets: Int) : SessionEvent

        /** 1件の訳が確定した。 */
        data class SegmentDone(val session: Int) : SessionEvent
        data class TranslationFailed(val session: Int, val message: String) : SessionEvent

        /** 判定B・合図：訳している・出している訳が無効になった。 */
        data class Invalidated(val reason: String) : SessionEvent

        /** ほかのアプリの画面に触れた（自分の窓への操作は除いてある）。 */
        data object OutsideTouched : SessionEvent
        data object MenuOpened : SessionEvent
        data object MenuClosed : SessionEvent
        data object Rotated : SessionEvent

        /** 翻訳モデルを切り替えた。 */
        data object ModelChanged : SessionEvent

        /** 上部の知らせをタップした。 */
        data object StatusTapped : SessionEvent
    }

    /** 行う作業。 */
    sealed interface Effect {
        /** 自分の表示を隠して画面を撮る。 */
        data class Capture(val session: Int) : Effect

        /** 判定Bの基準を、セッションで撮った画面にして判定を始める。 */
        data class BeginValidity(val session: Int) : Effect
        data class Recognize(val session: Int) : Effect

        /** 訳す文字が無かったと知らせる（訳を出していない状態で、判定Bは続ける）。 */
        data class NothingToTranslate(val session: Int) : Effect

        /** 空の訳の枠を出して、訳し始める。 */
        data class Translate(val session: Int) : Effect

        /** 全部の訳が確定した。 */
        data class Finished(val session: Int) : Effect
        data class Failed(val session: Int, val message: String) : Effect

        /** 触れたので、訳の枠を隠して枠の下をのぞく。 */
        data class Peek(val session: Int) : Effect

        /** 撮影・文字認識・翻訳・のぞきを取り消す。 */
        data class Cancel(val session: Int) : Effect
        data object ClearOverlay : Effect
        data object EndValidity : Effect

        /** 判定Aを最初から（待ちに入る）。 */
        data object ResetStart : Effect

        /** 浮遊ボタンの見た目（自動で訳すのを止めているときは薄く）。 */
        data class ButtonActive(val active: Boolean) : Effect

        /** 短い知らせ（トースト）。 */
        data class Notice(val text: String) : Effect
    }

    /** 出来事 [event] を受け取り、次の状態と作業を返す。 */
    fun reduce(state: State, event: SessionEvent): Pair<State, List<Effect>> = when (event) {
        SessionEvent.ButtonTapped -> when {
            state.trigger == Trigger.AUTO && state.autoOn -> rest(state.copy(autoOn = false)).plus(
                Effect.ButtonActive(false), Effect.Notice("自動翻訳を止めました。もう一度押すと再開します"),
            )
            state.trigger == Trigger.AUTO -> rest(state.copy(autoOn = true)).plus(Effect.ButtonActive(true))
            state.session != null -> rest(state)
            else -> capture(state)
        }

        is SessionEvent.TriggerChanged ->
            rest(state.copy(trigger = event.trigger, autoOn = event.trigger == Trigger.AUTO)).plus(Effect.ButtonActive(true))

        SessionEvent.Settled ->
            if (state.phase == Phase.Watching && !state.menuOpen) capture(state) else state to emptyList()

        is SessionEvent.Captured -> ifPhase<Phase.Capturing>(state, event.session) {
            if (event.ok) {
                state.copy(phase = Phase.Recognizing(event.session)) to
                    listOf(Effect.BeginValidity(event.session), Effect.Recognize(event.session))
            } else {
                rest(state).plus(Effect.Failed(event.session, "画面を撮影できませんでした"))
            }
        }

        is SessionEvent.Recognized -> ifPhase<Phase.Recognizing>(state, event.session) {
            if (event.targets == 0) {
                state.copy(phase = Phase.Showing(event.session)) to listOf(Effect.NothingToTranslate(event.session))
            } else {
                state.copy(phase = Phase.Translating(event.session, event.targets, 0)) to listOf(Effect.Translate(event.session))
            }
        }

        is SessionEvent.SegmentDone -> ifPhase<Phase.Translating>(state, event.session) { p ->
            val done = p.done + 1
            if (done >= p.total) {
                state.copy(phase = Phase.Showing(event.session)) to listOf(Effect.Finished(event.session))
            } else {
                state.copy(phase = p.copy(done = done)) to emptyList()
            }
        }

        is SessionEvent.TranslationFailed -> ifPhase<Phase.Translating>(state, event.session) {
            state.copy(phase = Phase.Showing(event.session)) to listOf(Effect.Failed(event.session, event.message))
        }

        is SessionEvent.Invalidated -> if (state.session != null) rest(state) else state to emptyList()

        SessionEvent.OutsideTouched -> {
            val s = state.session
            when {
                s == null -> state to emptyList()
                // 撮って訳している途中なら全部取り消す（2026-11-12、作者の要望「訳している途中に画面を操作したら全処理をキャンセル」）
                state.working -> rest(state)
                // タップで訳すとき：訳を出していれば消す（スクロールなどで画面が変わるので）
                state.trigger == Trigger.TAP -> rest(state)
                // 自動で訳すとき：訳の枠の下の文字が変わったかをのぞく（ゲームの台詞送り）
                else -> state to listOf(Effect.Peek(s))
            }
        }

        // 訳の枠はメニューより手前に出てメニューに触れなくなるので、開くときは訳を消し、訳している途中なら取り消す（2026-11-12、作者の指摘）
        SessionEvent.MenuOpened -> rest(state.copy(menuOpen = true))
        SessionEvent.MenuClosed -> {
            val next = state.copy(menuOpen = false)
            if (next.phase == Phase.Watching) next to listOf(Effect.ResetStart) else next to emptyList()
        }
        SessionEvent.Rotated -> rest(state)
        SessionEvent.ModelChanged -> rest(state)
        SessionEvent.StatusTapped -> rest(state)
    }

    /** 後片付けをして待ちに戻る（取り消しはすべてここを通る）。 */
    private fun rest(state: State): Pair<State, List<Effect>> {
        val effects = mutableListOf<Effect>()
        state.session?.let { effects += Effect.Cancel(it) }
        effects += Effect.ClearOverlay
        effects += Effect.EndValidity
        val watching = state.trigger == Trigger.AUTO && state.autoOn
        if (watching) effects += Effect.ResetStart
        return state.copy(phase = if (watching) Phase.Watching else Phase.Idle) to effects
    }

    /** 新しいセッションで撮り始める。 */
    private fun capture(state: State): Pair<State, List<Effect>> {
        val s = state.nextSession
        return state.copy(phase = Phase.Capturing(s), nextSession = s + 1) to listOf(Effect.Capture(s))
    }

    /** 今が [P] のセッション [session] なら [block] を、そうでなければ何もしない（遅れて届いた古いセッションの結果を捨てる）。 */
    private inline fun <reified P : Phase> ifPhase(
        state: State,
        session: Int,
        block: (P) -> Pair<State, List<Effect>>,
    ): Pair<State, List<Effect>> {
        val p = state.phase
        return if (p is P && state.session == session) block(p) else state to emptyList()
    }

    private fun Pair<State, List<Effect>>.plus(vararg more: Effect): Pair<State, List<Effect>> = first to second + more
}
