package app.plamotranslate.pipeline.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.Utterance

/**
 * 会話の1発話。原文は話した言語、訳文は訳した先の言語。表示の文（かかった時間、失敗のメッセージ）は画面の側が組み立てる。
 * [translated] は訳が終わったか（失敗しても true）、[failure] は失敗の理由。
 */
data class Turn(
    val id: Long,
    val direction: Direction,
    val original: String,
    val translation: String = "",
    val translated: Boolean = false,
    /** 発話を確定した時刻（会話ログに出す。聞き始めからでなく壁時計）。 */
    val timeMillis: Long = 0,
    /** 発話の長さと、認識・翻訳にかかった時間（ミリ秒）。 */
    val durationMs: Long = 0,
    val recognizeMs: Long = 0,
    val translateMs: Long? = null,
    val failure: String? = null,
    /** 音声認識の発話の番号（[Utterance.id]）。書き起こし直しで発話を指す。 */
    val utteranceId: Long? = null,
    /** 音声認識がこの発話の音声を取り置いているか（直近の発話。[SpeechEvent.Released] で false）。 */
    val retained: Boolean = false,
    /**
     * 利用者が言語を入れ替えて、書き起こし直しを待っている間の、入れ替える前の発話。入れ替えは先に向き（話し手）に反映し、
     * 原文は書き起こし直しが届いてから差し替える。書き起こし直せなければ、この発話に戻す。
     */
    val swappedFrom: Turn? = null,
    /** 言語の入れ替えについての状態（[ConversationSession] が決める。画面はこれだけを見る）。 */
    val swap: SwapState = SwapState.UNAVAILABLE,
    /** 確定訳の最初の文字を待つ間だけ表示する、聞き取り中のカードの途中訳。 */
    private val partialTranslation: String = "",
) {
    val displayTranslation: String get() = partialTranslation.ifEmpty { translation }

    internal fun withFinalProgress(text: String): Turn =
        copy(translation = text, partialTranslation = if (text.isBlank()) partialTranslation else "")
}

/** 確定したカードの言語の入れ替えについての状態（docs/設計/会話翻訳.md「カードの言語の取り違えを直す」）。 */
enum class SwapState {
    /** 入れ替えられる。 */
    AVAILABLE,

    /** 入れ替えて、書き起こし直しを待っている。 */
    REWRITING,

    /** 音声認識が音声を破棄したので、もう入れ替えられない（古いカード）。 */
    EXPIRED,

    /** 今は入れ替えられない（相手の言語がまだ決まっていない間の自分の発話など）。古いカードではない。 */
    UNAVAILABLE,
}

/**
 * 聞き取り中のカード（音声認識のカード [card] が開いてから確定するまで）。話し中（[open]）か、閉じて書き起こし待ち。
 * 確定すると [Turn] に置き換わる。
 * [translation] は途中の文字の訳（途中訳）。新しい途中の文字を訳し終わるまで、前の訳を出しておく。
 * [direction] は文字より先に決まることがある（書き起こしの重いモデルは、言語の識別で先に言語を知らせる）。
 * [startedAtMs] は話し始めた時刻（壁時計のミリ秒）。文字がまだ無い間、画面は話し始めからの秒数を出す。
 */
data class LiveSpeech(
    /** 音声認識のカードの番号（[SpeechEvent.CardOpened]）。確定した発話の [Utterance.id] と同じ。 */
    val card: Long,
    val text: String,
    val direction: Direction?,
    val translation: String = "",
    val startedAtMs: Long = 0,
    val open: Boolean = true,
)

/**
 * 会話の訳す順番の状態遷移（docs/設計/会話翻訳.md「翻訳への渡し方」）。音声の出来事と訳の結果を [handle] で受け取り、
 * 訳す方向を決め（[LanguageRouter]）、カードと聞き取り中の表示を持ち、翻訳のストリームに渡す作業（[Effect]）を返す。
 * 相手の言語が分かる前の自分の発話は訳さずに持ち、相手が決まったらさかのぼって訳す。利用者が相手の言語を選び直したら、
 * 自分の発話を新しい相手の言語へ訳し直す。
 *
 * 翻訳エンジンは1本のスレッドで1件ずつ動くので、確定訳を優先する。
 * - 確定訳は、話した順に1件ずつ渡す。
 * - 途中訳は、渡している途中訳が終わるまで、届いた途中の文字のうち最新のものだけを持つ（古いものは捨てる）。
 *   確定訳が残っている間は始めない。
 * - 確定した発話が来たら、走っている途中訳を取り消す。途中訳はカードの番号で持ち、話し中でなくなったカードの途中訳は始めない。
 *
 * 聞くことの始めと止める順番もここで決める：準備が済み、入力を使えると（[Event.InputAvailable]）、または手動で始めると（[Event.Started]）、聞き始める作業（[Effect.Begin]）を返す。止める
 * （[Event.StopRequested]）と、話していた分を確定させる作業（[Effect.Flush]）を返し、確定し終えたら（[SpeechEvent.Flushed]）
 * 聞き終える作業（[Effect.Finish]）を返す。画面を閉じたら（[Event.Closed]）確定を待たずに聞き終える。[Effect.Begin] と
 * [Effect.Finish] は1回ずつ対で返す。区切るボタンを押せるか（[canCut]）は、
 * 聞いていて話し中のカードがあるか。
 * 制御は状態を持つので、[handle] は1本のスレッドから呼ぶ。[handle] は状態を書き換え終えてから作業を返すので、
 * 作業を行う側が、その場で終わった作業の結果を [handle] に渡し直してよい（呼び出しの中からの呼び直し）。
 * 時刻は [clock]（壁時計のミリ秒）から読む。
 */
class ConversationSession(
    mode: PartnerMode = PartnerMode.AUTO,
    self: Language = Language.JA,
    /** 音声認識が聞ける言語（自動の相手を探す候補）。 */
    heard: List<Language> = SpeechModel.DEFAULT.languages,
    startWhenReady: Boolean = false,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        /** 音声認識の出来事。 */
        /** 利用者が発話者の言語を指定した場合は、その言語で翻訳方向を決める。 */
        data class Speech(val event: SpeechEvent, val specifiedLanguage: Language? = null) : Event

        /** 確定訳の途中（トークンごと）。 */
        data class FinalProgress(val turnId: Long, val text: String) : Event

        /** 確定訳が終わった。[failure] があれば失敗（取り消されたときも、理由を入れて返す）。 */
        data class FinalDone(val turnId: Long, val text: String, val millis: Long, val failure: String? = null) : Event

        /** 途中訳が終わった。取り消されたか失敗したら [text] は null。 */
        data class PartialDone(val requestId: Long, val text: String?) : Event

        data class SelfChanged(val self: Language) : Event
        data class ModeChanged(val mode: PartnerMode) : Event

        /** Android の会話画面で、音声認識と翻訳モデルが両方使えるようになった。 */
        data object ModelsReady : Event

        /** 利用者が開始を押した。入力を使えるか確認する。 */
        data object StartRequested : Event

        /** マイクの許可、または試験用の音声ファイルを確認した。 */
        data object InputAvailable : Event

        /** 聞き始めた（音声認識は新しい会話として始め直す）。 */
        data object Started : Event

        /** 聞くのを止める（停止ボタン、音声ファイルの終わり、入力の失敗）。話していた分を確定し終えてから止まる。 */
        data object StopRequested : Event

        /** 画面を閉じた。聞いていれば、確定を待たずに聞き終える（音声認識も一緒に閉じる）。 */
        data object Closed : Event

        /**
         * 音声認識の混み具合（[RecognitionLoad.urgent]：確定に要る書き起こしが残っている）。残っている間は途中訳を始めない。
         * 翻訳と音声認識が CPU を取り合うと、確定の書き起こしが遅れるため（docs/設計/会話翻訳.md「認識の段と CPU の割り当て」）。
         */
        data class RecognitionBusy(val busy: Boolean) : Event

        /**
         * 利用者が確定したカードの言語の取り違えを直す（カードを横にスライド）。自分の発話なら相手の言語、相手の発話なら
         * 自分の言語として書き起こし直してもらい（[Effect.Retranscribe]）、届いたら原文と向きを替えて訳し直す。
         * 利用者の指定は、音声認識の判定より先にする。向き（話し手と訳す先）はその場で替え（画面はすぐ新しい話し手のカードになる）、
         * 原文は書き起こし直しが届いてから差し替えて訳す。書き起こし直せなければ、入れ替える前のカードに戻す（原文の文字は
         * 指定した言語で書かれていないかもしれないので、言語だけを替えたままにはしない）。入れ替えられるのは [Turn.swap] が [SwapState.AVAILABLE] の発話だけ。
         */
        data class LanguageSwapped(val turnId: Long) : Event

        /** 画面の発話を消す。 */
        data object Cleared : Event
    }

    sealed interface Effect {
        /** 確定した発話を [target]（発話の向きの訳す先。null でないもの）へ訳す。終わったら [Event.FinalDone] を返す。 */
        data class TranslateFinal(val turn: Turn, val target: Language) : Effect

        /** 途中の文字を訳す。終わったら（取り消されても）[Event.PartialDone] を返す。 */
        data class TranslatePartial(val requestId: Long, val text: String, val source: Language, val target: Language) : Effect

        /** 渡している途中訳を取り消す（確定訳を先にするため）。 */
        data class CancelPartial(val requestId: Long) : Effect

        /** 発話 [utterance] を [language] として書き起こし直してもらう（[SpeechEngine.retranscribe]）。 */
        data class Retranscribe(val utterance: Long, val language: Language) : Effect

        /**
         * 音声の入力を止め、話していた分を確定させる（[SpeechEngine.flush]）。入力を止めてから flush する（後に渡した音声は
         * 次のカードになるため）。確定し終えると [SpeechEvent.Flushed] が届く。
         */
        data object Flush : Effect

        /** 聞き始める：聞いている間だけの設定にし、音声認識を新しい会話として始め直してから、音声の入力を始める。 */
        data object Begin : Effect

        /** 聞き終えた。音声の入力が残っていれば止め、聞いている間だけの設定を戻す（[Begin] と対）。 */
        data object Finish : Effect
    }

    private val router = LanguageRouter(mode, self, heard)

    val self: Language get() = router.self
    val mode: PartnerMode get() = router.mode

    /** 今、自分の発話を訳す先の言語。自動でまだ相手が話していなければ null。 */
    val partner: Language? get() = router.partner

    /** 相手の言語を自動で見つけたか。 */
    val partnerFound: Boolean get() = router.partnerFound

    /** 会話の言語（音声認識の区切りに渡す。話し手の交代は、この言語の間でだけ見る）。 */
    val speechLanguages: SpeechLanguages get() = router.speechLanguages

    var turns: List<Turn> = emptyList()
        private set

    /** 聞き取り中のカード（音声認識のカードの順）。話し中のカードと、閉じて書き起こし待ちのカード。 */
    var listeningCards: List<LiveSpeech> = emptyList()
        private set

    private enum class Phase { WAITING_FOR_MODELS, WAITING_FOR_INPUT, IDLE, LISTENING, STOPPING, CLOSED }

    private var phase = if (startWhenReady) Phase.WAITING_FOR_MODELS else Phase.IDLE

    val awaitingInput: Boolean get() = phase == Phase.WAITING_FOR_INPUT

    /** 聞いているか（止めて、確定し終えるのを待っている間も含む）。 */
    val listening: Boolean get() = phase == Phase.LISTENING || phase == Phase.STOPPING

    /** 区切るボタンを押せるか：聞いていて、話し中のカードがある。 */
    val canCut: Boolean get() = phase == Phase.LISTENING && listeningCards.any { it.open }

    private var nextTurnId = 0L
    private var nextRequestId = 0L

    private data class PartialRequest(val id: Long, val card: Long, val text: String, val source: Language, val target: Language)

    /** まだ渡していない確定した発話（話した順）。 */
    private val finalQueue = ArrayDeque<QueuedFinal>()

    /** 訳す列の1件：発話と、null でない訳す先。訳す先の無い発話（相手不明の間の自分の発話）は列に入れない。 */
    private data class QueuedFinal(val turn: Turn, val target: Language) {
        val id: Long get() = turn.id
    }

    /** 発話 [turn] に訳す先があれば、訳す列の後ろに入れる。 */
    private fun enqueue(turn: Turn) {
        turn.direction.target?.let { finalQueue.addLast(QueuedFinal(turn, it)) }
    }

    /** 渡している確定訳。 */
    private var finalRunning: Long? = null

    /**
     * 渡している確定訳が、訳す先を替える前の言語のものか（[retarget]）。そうなら、その訳の結果は発話に書かない。
     * 訳し直しは同じ発話の番号で列に入るが、走っている訳が終わるまで渡さないので、結果の取り違えは起きない。
     */
    private var finalStale = false

    private fun isStaleFinal(turnId: Long): Boolean = finalStale && finalRunning == turnId

    /** 次に渡す途中訳（最新の1件だけ）。 */
    private var partialPending: PartialRequest? = null

    /** 渡している途中訳。 */
    private var partialRunning: PartialRequest? = null

    /** 音声認識に確定に要る書き起こしが残っているか（[Event.RecognitionBusy]）。 */
    private var recognitionBusy = false

    fun handle(event: Event): List<Effect> {
        val effects = mutableListOf<Effect>()
        when (event) {
            is Event.Speech -> onSpeech(event.event, event.specifiedLanguage, effects)
            is Event.FinalProgress -> if (!isStaleFinal(event.turnId)) {
                updateTurn(event.turnId) { it.withFinalProgress(event.text) }
            }
            is Event.FinalDone -> {
                if (!isStaleFinal(event.turnId)) updateTurn(event.turnId) {
                    if (event.failure != null) {
                        it.copy(translated = true, failure = event.failure, partialTranslation = "")
                    } else {
                        it.copy(translation = event.text, translated = true, translateMs = event.millis, partialTranslation = "")
                    }
                }
                if (finalRunning == event.turnId) {
                    finalRunning = null
                    finalStale = false
                }
            }
            is Event.PartialDone -> {
                val request = partialRunning?.takeIf { it.id == event.requestId }
                if (request != null) partialRunning = null
                // 訳し終えたときにまだ聞き取り中のカードなら、そのカードに出す
                if (request != null && event.text != null) updateCard(request.card) {
                    if (it.direction?.source == request.source && it.direction.target == request.target) {
                        it.copy(translation = event.text)
                    } else it
                }
            }
            is Event.SelfChanged -> router.self = event.self
            is Event.ModeChanged -> {
                router.mode = event.mode
                retarget(onlyUntranslated = false)
            }
            Event.ModelsReady -> if (phase == Phase.WAITING_FOR_MODELS) phase = Phase.WAITING_FOR_INPUT
            Event.StartRequested -> if (phase == Phase.IDLE) phase = Phase.WAITING_FOR_INPUT
            Event.InputAvailable -> if (awaitingInput) begin(effects)
            Event.Started -> if (phase == Phase.IDLE) begin(effects)
            Event.Closed -> {
                if (listening) finish(effects)
                phase = Phase.CLOSED
            }
            Event.StopRequested -> when (phase) {
                Phase.WAITING_FOR_MODELS, Phase.WAITING_FOR_INPUT -> phase = Phase.IDLE
                Phase.LISTENING -> {
                    phase = Phase.STOPPING
                    partialPending = null
                    effects += Effect.Flush
                }
                else -> Unit
            }
            is Event.RecognitionBusy -> recognitionBusy = event.busy
            is Event.LanguageSwapped -> swapLanguage(event.turnId, effects)
            Event.Cleared -> {
                turns = emptyList()
                finalQueue.clear()
            }
        }
        dispatch(effects)
        refreshSwapState()
        return effects
    }

    private fun onSpeech(event: SpeechEvent, specifiedLanguage: Language?, effects: MutableList<Effect>) {
        when (event) {
            // 止めた後に開いたカードも、止める前に渡した音声のものなので、確定まで出す
            is SpeechEvent.CardOpened -> if (listening) {
                listeningCards = listeningCards + LiveSpeech(event.card, "", null, startedAtMs = clock())
            }
            is SpeechEvent.CardText -> onCardText(event, specifiedLanguage)
            is SpeechEvent.CardClosed -> updateCard(event.card) { it.copy(open = false) }
            is SpeechEvent.CardFinished -> {
                val card = listeningCards.firstOrNull { it.card == event.card }
                listeningCards = listeningCards.filterNot { it.card == event.card }
                event.utterance?.let { utterance ->
                    // 確定訳を優先する：途中訳は止める
                    partialRunning?.let { effects += Effect.CancelPartial(it.id) }
                    onUtterance(utterance, specifiedLanguage, card)
                }
            }
            // 止めて、話していた分を確定し終えた
            SpeechEvent.Flushed -> if (phase == Phase.STOPPING) finish(effects)
            is SpeechEvent.Retranscribed -> onRetranscribed(event)
            is SpeechEvent.RetranscribeFailed -> onRetranscribeFailed(event.id)
            is SpeechEvent.Released -> updateUtterance(event.id) { it.copy(retained = false) }
        }
    }

    /**
     * 話し中のカードの途中の文字。前の途中訳は、新しい途中の文字を訳し終わるまで出しておく（ちらつかせない）。
     * 文字の無い途中結果は言語の知らせ（カードの向きだけ決める）で、訳すものは無い。相手不明の自分の発話は訳さない。
     * 止めた後に届いた途中の文字は出さない（話していた分は確定で出る）。
     */
    private fun onCardText(event: SpeechEvent.CardText, specifiedLanguage: Language?) {
        if (phase != Phase.LISTENING) return
        val card = listeningCards.firstOrNull { it.card == event.card && it.open } ?: return
        val direction = specifiedLanguage?.let(router::chosen) ?: router.preview(event.text, event.lang)
        val keep = card.translation.takeIf { card.direction == direction }.orEmpty()
        updateCard(event.card) { it.copy(text = event.text, direction = direction, translation = keep) }
        val target = direction.target
        if (event.text.isNotEmpty() && target != null) {
            partialPending = PartialRequest(nextRequestId++, event.card, event.text, direction.source, target)
        }
    }

    private fun begin(effects: MutableList<Effect>) {
        phase = Phase.LISTENING
        listeningCards = emptyList()
        effects += Effect.Begin
    }

    private fun finish(effects: MutableList<Effect>) {
        phase = Phase.IDLE
        listeningCards = emptyList()
        partialPending = null
        effects += Effect.Finish
    }

    private fun updateCard(card: Long, change: (LiveSpeech) -> LiveSpeech) {
        listeningCards = listeningCards.map { if (it.card == card) change(it) else it }
    }

    private fun onUtterance(utterance: Utterance, specifiedLanguage: Language?, card: LiveSpeech?) {
        val direction = specifiedLanguage?.let(router::routeChosen) ?: router.route(utterance.text, utterance.lang)
        val turn = Turn(
            id = nextTurnId++,
            direction = direction,
            original = utterance.text,
            partialTranslation = card?.takeIf { it.direction == direction }?.translation.orEmpty(),
            timeMillis = clock(),
            durationMs = utterance.durationMs,
            recognizeMs = utterance.recognizeMs,
            utteranceId = utterance.id,
            retained = true,
        )
        turns = turns + turn
        // 相手が今決まったら、それまでの自分の発話をさかのぼって訳す（話した順に、この発話より前に）
        retarget(onlyUntranslated = true)
        enqueue(turn)
    }

    /**
     * 自分の発話の訳す先を、今の相手の言語にそろえて、訳し直す列に入れる。相手不明（自動を選び直した）なら何もしない
     * （訳した発話はそのまま残す）。自動で相手が見つかったとき（[onlyUntranslated]）は、相手不明で訳していなかった発話だけ。
     * 利用者が相手の言語を選んだときは、別の言語へ訳した発話も訳し直す。
     */
    private fun retarget(onlyUntranslated: Boolean) {
        val partner = router.partner ?: return
        val stale = turns.filter {
            it.direction.mine && it.direction.target != partner && (!onlyUntranslated || it.direction.target == null)
        }
        if (stale.isEmpty()) return
        val ids = stale.mapTo(HashSet()) { it.id }
        turns = turns.map {
            if (it.id in ids) it.forDirection(it.direction.copy(target = partner)) else it
        }
        requeue(ids)
    }

    /** 発話を入れ替えたときの話した言語（もう一方の話し手の言語）。相手がまだ決まっていなければ無い。 */
    private fun swappedLanguage(turn: Turn): Language? = if (turn.direction.mine) router.partner else router.self

    private fun Turn.forDirection(direction: Direction) =
        copy(direction = direction, translation = "", partialTranslation = "", translated = false, translateMs = null, failure = null)

    /**
     * 利用者がカードの言語を入れ替えられるか。音声認識が音声を取り置いていて、書き起こし直している最中でなく、もう一方の話し手の
     * 言語が決まっていること（相手がまだ決まっていない間の自分の発話は、相手の言語が分からないので直せない）。
     */
    private fun swapState(turn: Turn): SwapState = when {
        turn.swappedFrom != null -> SwapState.REWRITING
        turn.utteranceId != null && !turn.retained -> SwapState.EXPIRED
        turn.retained && swappedLanguage(turn) != null -> SwapState.AVAILABLE
        else -> SwapState.UNAVAILABLE
    }

    /**
     * 利用者が発話 [turnId] の言語を入れ替えた。向きをもう一方の話し手のものにその場で替え（訳はいったん空にし、走っている訳は
     * 使わない）、もう一方の話し手の言語として書き起こし直してもらう。
     */
    private fun swapLanguage(turnId: Long, effects: MutableList<Effect>) {
        val turn = turns.firstOrNull { it.id == turnId } ?: return
        val utterance = turn.utteranceId ?: return
        if (swapState(turn) != SwapState.AVAILABLE) return
        val language = swappedLanguage(turn) ?: return
        updateTurn(turnId) {
            it.forDirection(router.chosen(language)).copy(swappedFrom = it)
        }
        unqueue(turnId)
        effects += Effect.Retranscribe(utterance, language)
    }

    /** 書き起こし直しが届いた。原文を差し替えて訳す（向きは入れ替えたときに替えてある）。 */
    private fun onRetranscribed(event: SpeechEvent.Retranscribed) {
        val turn = turns.firstOrNull { it.utteranceId == event.id && it.swappedFrom != null } ?: return
        updateTurn(turn.id) { it.copy(original = event.utterance.text, swappedFrom = null) }
        requeue(setOf(turn.id))
    }

    /**
     * 書き起こし直せなかった。入れ替える前の原文と話し手に戻す。訳す先は今の相手の言語で決め直す（待っている間に利用者が
     * 相手の言語を選び直していれば、前の訳は使わず訳し直す。その間に訳が止まっていても訳し直す）。
     */
    private fun onRetranscribeFailed(id: Long) {
        val turn = turns.firstOrNull { it.utteranceId == id } ?: return
        val before = turn.swappedFrom ?: return
        val target = if (before.direction.mine) router.partner else router.self
        val retargeted = before.direction.target != target
        val restored = (if (retargeted) before.forDirection(before.direction.copy(target = target)) else before)
            .copy(retained = turn.retained)
        updateTurn(turn.id) { restored }
        if (!restored.translated) requeue(setOf(turn.id))
    }

    private fun updateUtterance(id: Long, change: (Turn) -> Turn) {
        turns = turns.map { if (it.utteranceId == id) change(it) else it }
    }

    /** 入れ替えの状態を、発話と言語の状態にそろえる（相手が決まった・音声を破棄した・書き起こし直し始めた、で変わる）。 */
    private fun refreshSwapState() {
        if (turns.any { it.swap != swapState(it) }) turns = turns.map { it.copy(swap = swapState(it)) }
    }

    /**
     * 発話 [ids] を訳し直す列に入れる（話した順）。走っている訳がそのどれかなら、その結果は書かない。
     * 書き起こし直しを待っている発話は、原文がまだ前の言語のものなので入れない（届いたときに入れる）。
     */
    private fun requeue(ids: Set<Long>) {
        ids.forEach(::unqueue)
        turns.filter { it.id in ids && it.swappedFrom == null }.forEach(::enqueue)
    }

    /** 発話 [id] を訳す列から外す。走っている訳がそれなら、その結果は書かない。 */
    private fun unqueue(id: Long) {
        if (finalRunning == id) finalStale = true
        finalQueue.removeAll { it.id == id }
    }

    /** 空いていれば、次の確定訳と途中訳を渡す。 */
    private fun dispatch(effects: MutableList<Effect>) {
        if (finalRunning == null) {
            finalQueue.removeFirstOrNull()?.let { next ->
                finalRunning = next.id
                effects += Effect.TranslateFinal(next.turn, next.target)
            }
        }
        if (partialRunning != null) return
        val request = partialPending ?: return
        partialPending = null
        // 確定訳が残っている間と、話し中でなくなったカードの途中の文字は訳さない（次の途中の文字を待つ）
        if (finalRunning != null || finalQueue.isNotEmpty() || listeningCards.none { it.card == request.card && it.open }) return
        // 音声認識が確定を書き起こしている間は始めない。最新の1件を持っておき、空いたら渡す
        if (recognitionBusy) {
            partialPending = request
            return
        }
        partialRunning = request
        effects += Effect.TranslatePartial(request.id, request.text, request.source, request.target)
    }

    private fun updateTurn(id: Long, change: (Turn) -> Turn) {
        turns = turns.map { if (it.id == id) change(it) else it }
    }
}
