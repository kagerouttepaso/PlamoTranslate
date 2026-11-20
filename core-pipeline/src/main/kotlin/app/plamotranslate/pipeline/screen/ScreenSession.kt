package app.plamotranslate.pipeline.screen

/**
 * 画面翻訳の状態遷移（docs/設計/画面翻訳.md「状態遷移」）。画面翻訳の制御は、ここだけが決める。
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

        /**
         * 訳を出している（訳す文字が無かったときは、ステータス表示だけを出している）。[peeking] は、触れたので訳の枠を隠して
         * 枠の下をのぞいている間（のぞき終えるまで、次ののぞきは始めない）。
         */
        data class Showing(val session: Int, val peeking: Boolean = false) : Phase
    }

    data class State(
        val trigger: Trigger,
        /** 自動で訳すのを動かしているか（フローティングボタンで止められる）。 */
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

        /** 判定Bの規則3（自分の表示の外の画面が変わった）を使うか。自動で訳すときだけ（タップで訳すときは、触れたら消すので要らない）。 */
        val watchOutside: Boolean get() = trigger == Trigger.AUTO
    }

    fun initial(trigger: Trigger): State =
        State(trigger, autoOn = trigger == Trigger.AUTO, phase = if (trigger == Trigger.AUTO) Phase.Watching else Phase.Idle)

    /** 始めるときの状態と作業（自動で訳すなら判定Aの待ちに入る）。 */
    fun start(trigger: Trigger): Pair<State, List<Effect>> =
        initial(trigger).let { it to if (it.phase == Phase.Watching) listOf(Effect.ResetStart) else emptyList() }

    /** 起きたこと。 */
    sealed interface SessionEvent {
        /** フローティングボタンのタップ。 */
        data object ButtonTapped : SessionEvent
        data class TriggerChanged(val trigger: Trigger) : SessionEvent

        /** 判定A：画面が落ち着いた。 */
        data object Settled : SessionEvent
        data class Captured(val session: Int, val ok: Boolean) : SessionEvent
        data class Recognized(val session: Int, val targets: Int) : SessionEvent

        /** 1件の訳が確定した。 */
        data class SegmentDone(val session: Int) : SessionEvent

        /** 訳の枠を並べるウィンドウを置けなかった（重ね表示の許可を途中で外したときなど）。 */
        data class BoxesUnplaced(val session: Int) : SessionEvent
        data class TranslationFailed(val session: Int, val message: String) : SessionEvent

        /** 判定B・イベント：訳している・出している訳が無効になった。 */
        data class Invalidated(val reason: String) : SessionEvent

        /** ほかのアプリの画面に触れた（自分のウィンドウへの操作は除いてある）。 */
        data object OutsideTouched : SessionEvent

        /** のぞき終えた（[Effect.Peek] の結果）。[invalid] は枠の下が変わっていたときの理由で、変わっていなければ null。 */
        data class Peeked(val session: Int, val invalid: String?) : SessionEvent
        data object MenuOpened : SessionEvent
        data object MenuClosed : SessionEvent
        data object Rotated : SessionEvent

        /** 翻訳モデルを切り替えた。 */
        data object ModelChanged : SessionEvent

        /** ステータス表示をタップした。 */
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

        /** 画面を撮影できなかったと知らせる。 */
        data class CaptureFailed(val session: Int) : Effect

        /** 訳の枠を出せなかったと知らせる。 */
        data class BoxesFailed(val session: Int) : Effect

        /** 翻訳に失敗したと知らせる。 */
        data class Failed(val session: Int, val message: String) : Effect

        /** 触れたので、訳の枠を隠して枠の下をのぞく。結果は [SessionEvent.Peeked] で戻る。 */
        data class Peek(val session: Int) : Effect

        /** のぞいて変わっていなかったので、隠した訳の枠を戻す。 */
        data class ShowBoxes(val session: Int) : Effect

        /**
         * 撮影・文字認識・翻訳・のぞきを取り消し、セッションの材料を捨てる。[reason] は理由（記録に書く）。[working] は
         * 撮ってから訳し終わるまでの途中だったか（途中なら「取り消した」、訳を出していたなら「訳を消した」と記録する）。
         */
        data class Cancel(val session: Int, val reason: String, val working: Boolean) : Effect
        data object ClearOverlay : Effect
        data object EndValidity : Effect

        /** 判定Aを最初から（待ちに入る）。 */
        data object ResetStart : Effect

        /** 判定Aをやめる（撮り始めたとき、自動で訳すのをやめたとき）。 */
        data object EndStart : Effect

        /** フローティングボタンの見た目（自動で訳すのを止めているときは薄く）。 */
        data class ButtonActive(val active: Boolean) : Effect

        /** 短いメッセージ（トースト）。 */
        data class Notice(val text: String) : Effect
    }

    /** 出来事 [event] を受け取り、次の状態と作業を返す。 */
    fun reduce(state: State, event: SessionEvent): Pair<State, List<Effect>> = when (event) {
        SessionEvent.ButtonTapped -> when {
            state.trigger == Trigger.AUTO && state.autoOn -> rest(state.copy(autoOn = false), "自動翻訳を止めた").plus(
                Effect.ButtonActive(false), Effect.Notice("自動翻訳を止めました。もう一度押すと再開します"),
            )
            state.trigger == Trigger.AUTO -> rest(state.copy(autoOn = true), "自動翻訳を再開した").plus(Effect.ButtonActive(true))
            state.session != null -> rest(state, "ボタンを押した")
            else -> capture(state)
        }

        is SessionEvent.TriggerChanged ->
            rest(state.copy(trigger = event.trigger, autoOn = event.trigger == Trigger.AUTO), "始め方を変えた").plus(Effect.ButtonActive(true))

        SessionEvent.Settled ->
            if (state.phase == Phase.Watching && !state.menuOpen) capture(state) else state to emptyList()

        is SessionEvent.Captured -> ifPhase<Phase.Capturing>(state, event.session) {
            if (event.ok) {
                state.copy(phase = Phase.Recognizing(event.session)) to
                    listOf(Effect.BeginValidity(event.session), Effect.Recognize(event.session))
            } else {
                rest(state, "画面を撮影できなかった").plus(Effect.CaptureFailed(event.session))
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

        // 枠を出せなければ訳しても見せられないので、取り消して知らせる（件数と時間を出して終えたように見せない）
        is SessionEvent.BoxesUnplaced -> ifPhase<Phase.Translating>(state, event.session) {
            rest(state, "訳の枠を置けなかった").plus(Effect.BoxesFailed(event.session))
        }

        is SessionEvent.TranslationFailed -> ifPhase<Phase.Translating>(state, event.session) {
            state.copy(phase = Phase.Showing(event.session)) to listOf(Effect.Failed(event.session, event.message))
        }

        is SessionEvent.Invalidated -> if (state.session != null) rest(state, event.reason) else state to emptyList()

        SessionEvent.OutsideTouched -> {
            val s = state.session
            when {
                s == null -> state to emptyList()
                // 撮って訳している途中なら全部取り消す（2026-11-12、作者の要望「訳している途中に画面を操作したら全処理をキャンセル」）
                state.working -> rest(state, "訳している途中に画面を操作した")
                // タップで訳すとき：訳を出していれば消す（スクロールなどで画面が変わるので）
                state.trigger == Trigger.TAP -> rest(state, "画面に触れた")
                // 自動で訳すとき：訳の枠の下の文字が変わったかをのぞく（ゲームの台詞送り）。のぞいている間は重ねない
                else -> ifPhase<Phase.Showing>(state, s) { p ->
                    if (p.peeking) state to emptyList() else state.copy(phase = p.copy(peeking = true)) to listOf(Effect.Peek(s))
                }
            }
        }

        // のぞいて枠の下が変わっていれば取り消し、変わっていなければ枠を戻す。表示をやめる遷移はどれも rest を通り、
        // のぞきの作業も Cancel で止まるので、のぞいている間に表示をやめたときの結果は届かない（届いても ifPhase で捨てる）
        is SessionEvent.Peeked -> ifPhase<Phase.Showing>(state, event.session) { p ->
            when {
                !p.peeking -> state to emptyList()
                event.invalid != null -> rest(state, event.invalid)
                else -> state.copy(phase = p.copy(peeking = false)) to listOf(Effect.ShowBoxes(event.session))
            }
        }

        // 訳の枠はメニューより手前に出てメニューに触れなくなるので、開くときは訳を消し、訳している途中なら取り消す（2026-11-12、作者の指摘）
        SessionEvent.MenuOpened -> rest(state.copy(menuOpen = true), "メニューを開いた")
        SessionEvent.MenuClosed -> {
            val next = state.copy(menuOpen = false)
            if (next.phase == Phase.Watching) next to listOf(Effect.ResetStart) else next to emptyList()
        }
        SessionEvent.Rotated -> rest(state, "画面を回した")
        SessionEvent.ModelChanged -> rest(state, "翻訳モデルを変えた")
        SessionEvent.StatusTapped -> rest(state, "ステータス表示に触れた")
    }

    /** 後片付けをして待ちに戻る（取り消しはすべてここを通る）。[reason] は取り消した理由。 */
    private fun rest(state: State, reason: String): Pair<State, List<Effect>> {
        val effects = mutableListOf<Effect>()
        state.session?.let { effects += Effect.Cancel(it, reason, state.working) }
        effects += Effect.ClearOverlay
        effects += Effect.EndValidity
        val watching = state.trigger == Trigger.AUTO && state.autoOn
        if (watching) effects += Effect.ResetStart else if (state.phase == Phase.Watching) effects += Effect.EndStart
        return state.copy(phase = if (watching) Phase.Watching else Phase.Idle) to effects
    }

    /** 新しいセッションで撮り始める。 */
    private fun capture(state: State): Pair<State, List<Effect>> {
        val s = state.nextSession
        val effects = if (state.phase == Phase.Watching) listOf(Effect.EndStart, Effect.Capture(s)) else listOf(Effect.Capture(s))
        return state.copy(phase = Phase.Capturing(s), nextSession = s + 1) to effects
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

/**
 * 出来事を順に [ScreenSession.reduce] へ渡し、返った作業を [perform] で行う。作業を行っている間に届いた出来事（作業の中から
 * 同期で出たものを含む）は、今の出来事の作業をすべて行ってから順に渡す。作業の途中で状態遷移へ入り直すと、外側の残りの作業が
 * 内側の後片付けの後に走るため（取り消したセッションの作業が走る）。メインスレッドだけで使う。
 */
class ScreenSessionLoop(trigger: ScreenSession.Trigger, private val perform: (ScreenSession.Effect) -> Unit) {
    var state: ScreenSession.State = ScreenSession.initial(trigger)
        private set
    private val pending = ArrayDeque<ScreenSession.SessionEvent>()
    private var running = false

    /** 始め直す（始め方の設定を読み直したとき）。 */
    fun start(trigger: ScreenSession.Trigger) {
        val (next, effects) = ScreenSession.start(trigger)
        state = next
        run(effects)
    }

    fun dispatch(event: ScreenSession.SessionEvent) {
        pending.addLast(event)
        if (running) return
        running = true
        try {
            while (pending.isNotEmpty()) {
                val (next, effects) = ScreenSession.reduce(state, pending.removeFirst())
                state = next
                effects.forEach(perform)
            }
        } finally {
            running = false
        }
    }

    private fun run(effects: List<ScreenSession.Effect>) {
        running = true
        try {
            effects.forEach(perform)
        } finally {
            running = false
        }
        if (pending.isNotEmpty()) dispatch(pending.removeFirst())
    }
}
