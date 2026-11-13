package app.plamotranslate.pipeline.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.Utterance

/**
 * 会話の1発話。原文は話した言語、訳文は訳した先の言語。表示の文（かかった時間、失敗の知らせ）は画面の側が組み立てる。
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
)

/**
 * 話している最中の聞き取り。話し終わると [Turn] に置き換わる。
 * [translation] は途中の文字の訳（途中訳）。新しい途中の文字を訳し終わるまで、前の訳を出しておく。
 */
data class LiveSpeech(val text: String, val direction: Direction?, val translation: String = "")

/**
 * 会話の訳す順番の状態遷移（docs/設計/会話翻訳.md「翻訳への渡し方」）。音声の出来事と訳の結果を [handle] で受け取り、
 * 訳す方向を決め（[LanguageRouter]）、カードと聞き取り中の表示を持ち、翻訳の流れに渡す作業（[Effect]）を返す。
 *
 * 翻訳エンジンは1本のスレッドで1件ずつ動くので、確定訳を優先する。
 * - 確定訳は、話した順に1件ずつ渡す。
 * - 途中訳は、渡している途中訳が終わるまで、届いた途中の文字のうち最新のものだけを持つ（古いものは捨てる）。
 *   確定訳が残っている間は始めない。
 * - 確定した発話が来たら、走っている途中訳を取り消す。話し始め・確定のたびに世代を進め、古い世代の途中訳は出さない。
 * 制御は状態を持つので、[handle] は1本のスレッドから呼ぶ。[handle] は状態を書き換え終えてから作業を返すので、
 * 作業を行う側が、その場で終わった作業の結果を [handle] に渡し直してよい（呼び出しの中からの呼び直し）。
 * 時刻は [clock]（壁時計のミリ秒）から読む。
 */
class ConversationSession(
    mode: PartnerMode = PartnerMode.AUTO,
    self: Language = Language.JA,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    sealed interface Event {
        /** 音声認識の出来事。 */
        data class Speech(val event: SpeechEvent) : Event

        /** 確定訳の途中（トークンごと）。 */
        data class FinalProgress(val turnId: Long, val text: String) : Event

        /** 確定訳が終わった。[failure] があれば失敗（取り消されたときも、理由を入れて返す）。 */
        data class FinalDone(val turnId: Long, val text: String, val millis: Long, val failure: String? = null) : Event

        /** 途中訳が終わった。取り消されたか失敗したら [text] は null。 */
        data class PartialDone(val requestId: Long, val text: String?) : Event

        data class SelfChanged(val self: Language) : Event
        data class ModeChanged(val mode: PartnerMode) : Event

        /** 聞くのを止めた（聞き取り中の表示を消す）。 */
        data object Stopped : Event

        /** 画面の発話を消す。 */
        data object Cleared : Event
    }

    sealed interface Effect {
        /** 確定した発話を訳す。終わったら [Event.FinalDone] を返す。 */
        data class TranslateFinal(val turn: Turn) : Effect

        /** 途中の文字を訳す。終わったら（取り消されても）[Event.PartialDone] を返す。 */
        data class TranslatePartial(val requestId: Long, val text: String, val direction: Direction) : Effect

        /** 渡している途中訳を取り消す（確定訳を先にするため）。 */
        data class CancelPartial(val requestId: Long) : Effect
    }

    private val router = LanguageRouter(mode, self)

    val self: Language get() = router.self
    val mode: PartnerMode get() = router.mode

    /** 今、自分の発話を訳す先の言語。 */
    val partner: Language get() = router.partner

    /** 中国語を日本語と別の話し手の言語として扱うか（音声認識の区切りに渡す）。 */
    val chinese: Boolean get() = router.chinese

    var turns: List<Turn> = emptyList()
        private set

    /** 話している最中なら、ここまでの聞き取り。 */
    var live: LiveSpeech? = null
        private set

    private var nextTurnId = 0L
    private var nextRequestId = 0L

    /** 話し始め・確定のたびに進める世代。途中訳が古い発話のものか見分ける。 */
    private var generation = 0L

    private data class PartialRequest(val id: Long, val generation: Long, val text: String, val direction: Direction)

    /** まだ渡していない確定した発話（話した順）。 */
    private val finalQueue = ArrayDeque<Turn>()

    /** 渡している確定訳。 */
    private var finalRunning: Long? = null

    /** 次に渡す途中訳（最新の1件だけ）。 */
    private var partialPending: PartialRequest? = null

    /** 渡している途中訳。 */
    private var partialRunning: PartialRequest? = null

    fun handle(event: Event): List<Effect> {
        val effects = mutableListOf<Effect>()
        when (event) {
            is Event.Speech -> onSpeech(event.event, effects)
            is Event.FinalProgress -> updateTurn(event.turnId) { it.copy(translation = event.text) }
            is Event.FinalDone -> {
                updateTurn(event.turnId) {
                    if (event.failure != null) {
                        it.copy(translated = true, failure = event.failure)
                    } else {
                        it.copy(translation = event.text, translated = true, translateMs = event.millis)
                    }
                }
                if (finalRunning == event.turnId) finalRunning = null
            }
            is Event.PartialDone -> {
                val request = partialRunning?.takeIf { it.id == event.requestId }
                if (request != null) partialRunning = null
                // 訳し終えたときにまだ同じ発話なら、聞き取り中の表示に出す
                if (request != null && event.text != null && request.generation == generation) {
                    live = live?.copy(translation = event.text)
                }
            }
            is Event.SelfChanged -> router.self = event.self
            is Event.ModeChanged -> router.mode = event.mode
            Event.Stopped -> live = null
            Event.Cleared -> turns = emptyList()
        }
        dispatch(effects)
        return effects
    }

    private fun onSpeech(event: SpeechEvent, effects: MutableList<Effect>) {
        when (event) {
            is SpeechEvent.Speaking -> {
                generation++
                live = if (event.speaking) LiveSpeech("", null) else null
            }
            is SpeechEvent.Partial -> {
                val direction = router.preview(event.text, event.lang)
                // 前の途中訳は、新しい途中の文字を訳し終わるまで出しておく（ちらつかせない）
                val keep = live?.takeIf { it.direction == direction }?.translation.orEmpty()
                live = LiveSpeech(event.text, direction, keep)
                partialPending = PartialRequest(nextRequestId++, generation, event.text, direction)
            }
            is SpeechEvent.Final -> {
                // 確定訳を優先する：古い発話の途中訳は要らないので止める
                generation++
                partialRunning?.let { effects += Effect.CancelPartial(it.id) }
                onUtterance(event.utterance)
            }
        }
    }

    private fun onUtterance(utterance: Utterance) {
        val direction = router.route(utterance.text, utterance.lang)
        val turn = Turn(
            id = nextTurnId++,
            direction = direction,
            original = utterance.text,
            timeMillis = clock(),
            durationMs = utterance.durationMs,
            recognizeMs = utterance.recognizeMs,
        )
        turns = turns + turn
        // 話し続けていれば次の途中結果が来るので、ここでは聞き取り中の表示だけ空にする
        live = live?.let { LiveSpeech("", null) }
        finalQueue.addLast(turn)
    }

    /** 空いていれば、次の確定訳と途中訳を渡す。 */
    private fun dispatch(effects: MutableList<Effect>) {
        if (finalRunning == null) {
            finalQueue.removeFirstOrNull()?.let { turn ->
                finalRunning = turn.id
                effects += Effect.TranslateFinal(turn)
            }
        }
        if (partialRunning != null) return
        val request = partialPending ?: return
        partialPending = null
        // 確定訳が残っている間と、古い発話の途中の文字は訳さない
        if (finalRunning != null || finalQueue.isNotEmpty() || request.generation != generation) return
        partialRunning = request
        effects += Effect.TranslatePartial(request.id, request.text, request.direction)
    }

    private fun updateTurn(id: Long, change: (Turn) -> Turn) {
        turns = turns.map { if (it.id == id) change(it) else it }
    }
}
