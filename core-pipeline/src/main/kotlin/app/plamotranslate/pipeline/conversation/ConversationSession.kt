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
)

/**
 * 話している最中の聞き取り。話し終わると [Turn] に置き換わる。
 * [translation] は途中の文字の訳（途中訳）。新しい途中の文字を訳し終わるまで、前の訳を出しておく。
 * [direction] は文字より先に決まることがある（書き起こしの重いモデルは、言語の識別で先に言語を知らせる）。
 * [startedAtMs] は話し始めた時刻（壁時計のミリ秒）。文字がまだ無い間、画面は話し始めからの秒数を出す。
 */
data class LiveSpeech(
    val text: String,
    val direction: Direction?,
    val translation: String = "",
    val startedAtMs: Long = 0,
    /** 書き起こし待ちのカード（[ConversationSession.queued]）の見分け。話し中のカードは0。 */
    val id: Long = 0,
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
 * - 確定した発話が来たら、走っている途中訳を取り消す。話し始め・確定のたびに世代を進め、古い世代の途中訳は出さない。
 * 制御は状態を持つので、[handle] は1本のスレッドから呼ぶ。[handle] は状態を書き換え終えてから作業を返すので、
 * 作業を行う側が、その場で終わった作業の結果を [handle] に渡し直してよい（呼び出しの中からの呼び直し）。
 * 時刻は [clock]（壁時計のミリ秒）から読む。
 */
class ConversationSession(
    mode: PartnerMode = PartnerMode.AUTO,
    self: Language = Language.JA,
    /** 音声認識が聞ける言語（自動の相手を探す候補）。 */
    heard: List<Language> = SpeechModel.DEFAULT.languages,
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

        /**
         * 区切るボタンが押された。音声認識より先に、画面の話し中のカードを書き起こし待ちの列へ移し、新しい話し中のカードを始める。
         * 音声認識が押した位置まで追いついて区切ると、確定（[SpeechEvent.Final]）と [SpeechEvent.CutDone] が届く。
         */
        data object CutRequested : Event

        /**
         * 音声認識の混み具合（[RecognitionLoad.urgent]：確定に要る書き起こしが残っている）。残っている間は途中訳を始めない。
         * 翻訳と音声認識が CPU を取り合うと、確定の書き起こしが遅れるため（docs/設計/会話翻訳.md「認識の段と CPU の割り当て」）。
         */
        data class RecognitionBusy(val busy: Boolean) : Event

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

    /** 話している最中なら、ここまでの聞き取り。 */
    var live: LiveSpeech? = null
        private set

    /**
     * 区切るボタンで区切ったが、まだ確定していないカード（古い順）。書き起こしの重いモデルは認識が遅れるので、
     * 押した時にカードが区切られたことを見せる。区切る前の音声の途中結果は、列の先頭のカードを更新する。
     */
    var queued: List<LiveSpeech> = emptyList()
        private set

    private var nextQueuedId = 1L

    private var nextTurnId = 0L
    private var nextRequestId = 0L

    /** 話し始め・確定のたびに進める世代。途中訳が古い発話のものか見分ける。 */
    private var generation = 0L

    private data class PartialRequest(val id: Long, val generation: Long, val text: String, val direction: Direction)

    /** まだ渡していない確定した発話（話した順）。 */
    private val finalQueue = ArrayDeque<Turn>()

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
            is Event.Speech -> onSpeech(event.event, effects)
            is Event.FinalProgress -> if (!isStaleFinal(event.turnId)) updateTurn(event.turnId) { it.copy(translation = event.text) }
            is Event.FinalDone -> {
                if (!isStaleFinal(event.turnId)) updateTurn(event.turnId) {
                    if (event.failure != null) {
                        it.copy(translated = true, failure = event.failure)
                    } else {
                        it.copy(translation = event.text, translated = true, translateMs = event.millis)
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
                // 訳し終えたときにまだ同じ発話なら、聞き取り中の表示に出す
                if (request != null && event.text != null && request.generation == generation) {
                    live = live?.copy(translation = event.text)
                }
            }
            is Event.SelfChanged -> router.self = event.self
            is Event.ModeChanged -> {
                router.mode = event.mode
                retarget(onlyUntranslated = false)
            }
            Event.CutRequested -> live?.let {
                queued = queued + it.copy(id = nextQueuedId++)
                // 走っている途中訳は区切った前のカードのものなので、新しい話し中のカードには出さない
                generation++
                live = LiveSpeech("", null, startedAtMs = clock())
            }
            is Event.RecognitionBusy -> recognitionBusy = event.busy
            Event.Stopped -> {
                live = null
                queued = emptyList()
            }
            Event.Cleared -> {
                turns = emptyList()
                finalQueue.clear()
            }
        }
        dispatch(effects)
        return effects
    }

    private fun onSpeech(event: SpeechEvent, effects: MutableList<Effect>) {
        when (event) {
            // 区切るボタンの区切りを終えた（確定の後に届く）。書き起こし待ちの列の先頭を消す（確定したカードに置き換わる）
            SpeechEvent.CutDone -> queued = queued.drop(1)
            SpeechEvent.Flushed -> Unit
            is SpeechEvent.Speaking -> {
                generation++
                live = if (event.speaking) LiveSpeech("", null, startedAtMs = clock()) else null
            }
            is SpeechEvent.Partial -> {
                val direction = router.preview(event.text, event.lang)
                // 区切った位置より前の音声の途中結果は、書き起こし待ちの先頭のカードのもの。訳し直さない
                queued.firstOrNull()?.let { head ->
                    if (event.text.isNotEmpty() || head.text.isEmpty()) {
                        queued = listOf(head.copy(text = event.text.ifEmpty { head.text }, direction = direction)) + queued.drop(1)
                    }
                    return
                }
                // 前の途中訳は、新しい途中の文字を訳し終わるまで出しておく（ちらつかせない）
                val keep = live?.takeIf { it.direction == direction }?.translation.orEmpty()
                live = LiveSpeech(event.text, direction, keep, live?.startedAtMs ?: clock())
                // 文字の無い途中結果は言語の知らせ（カードの向きだけ決める）。訳すものは無い。相手不明の自分の発話も訳さない
                if (event.text.isNotEmpty() && direction.target != null) partialPending = PartialRequest(nextRequestId++, generation, event.text, direction)
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
        // 相手が今決まったら、それまでの自分の発話をさかのぼって訳す（話した順に、この発話より前に）
        retarget(onlyUntranslated = true)
        // 話し続けていれば次の途中結果が来るので、ここでは聞き取り中の表示だけ空にする。
        // 書き起こし待ちがあれば、この確定はその分なので、ボタンの後に始めた話し中のカードはそのままにする
        if (queued.isEmpty()) live = live?.let { LiveSpeech("", null, startedAtMs = clock()) }
        if (direction.target != null) finalQueue.addLast(turn)
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
            if (it.id in ids) it.copy(direction = it.direction.copy(target = partner), translation = "", translated = false, translateMs = null, failure = null) else it
        }
        if (finalRunning in ids) finalStale = true
        finalQueue.removeAll { it.id in ids }
        turns.filter { it.id in ids }.forEach { finalQueue.addLast(it) }
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
        // 確定訳が残っている間と、古い発話の途中の文字は訳さない（次の途中の文字を待つ）
        if (finalRunning != null || finalQueue.isNotEmpty() || request.generation != generation) return
        // 音声認識が確定を書き起こしている間は始めない。最新の1件を持っておき、空いたら渡す
        if (recognitionBusy) {
            partialPending = request
            return
        }
        partialRunning = request
        effects += Effect.TranslatePartial(request.id, request.text, request.direction)
    }

    private fun updateTurn(id: Long, change: (Turn) -> Turn) {
        turns = turns.map { if (it.id == id) change(it) else it }
    }
}
