package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.llm.sameAs
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.conversation.Turn
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import kotlin.math.log10

internal enum class AudioPhase(val label: String) {
    IDLE("停止中"), STARTING("音声入力を準備中…"), LISTENING("聞き取り中…"), STOPPING("話していた分を確定中…"),
}

internal data class AudioSelection(
    val source: AudioSource = AudioSource.MICROPHONE,
    val self: Language = Language.JA,
    val partner: Language? = null,
    val playbackFrom: Language = Language.EN,
    val playbackTo: Language = Language.JA,
    val recognition: DesktopRecognitionSettings = DesktopRecognitionSettings(),
) {
    val languages get() = recognition.model.languages

    /**
     * 話す言語が先に決まっているか。マイクは話した言語で話し手を決める（Android 版と同じ）ので決めない。
     * 再生音は原文の言語を利用者が選ぶ。
     */
    val spoken: Language?
        get() = when (source) {
            AudioSource.MICROPHONE -> null
            AudioSource.PLAYBACK -> playbackFrom
        }

    val specifiedCode: String? get() = spoken?.speechCode

    fun session(): ConversationSession = when (source) {
        AudioSource.MICROPHONE -> ConversationSession(PartnerMode(partner), self, languages)
        AudioSource.PLAYBACK -> ConversationSession(PartnerMode(playbackFrom), playbackTo, languages)
    }
}

/**
 * 会話翻訳の画面の状態。カードは中核の [ConversationSession] のもの（話し中・書き起こし待ちの [LiveSpeech] と確定した [Turn]）を
 * そのまま渡し、見せ方は画面が決める（Android 版の `ConversationCards.kt` と同じ分け方）。
 * [live] と [turns] は古い順。
 */
internal data class AudioState(
    val selection: AudioSelection,
    val phase: AudioPhase = AudioPhase.IDLE,
    val live: List<LiveSpeech> = emptyList(),
    val turns: List<Turn> = emptyList(),
    val subtitle: SubtitleLine? = null,
    val subtitleVisible: Boolean = false,
    val canCut: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val partner: Language? = null,
    val partnerFound: Boolean = false,
    val canExport: Boolean = false,
    val automaticAvailable: Boolean = true,
    /** 音声認識が録音からどれだけ遅れているか（秒）。 */
    val lagSeconds: Float = 0f,
) {
    /** 音の出どころ・認識の設定・再生音の言語は、止めているときだけ変える（認識器とセッションを作り直す）。 */
    val canConfigure get() = phase == AudioPhase.IDLE

    /** マイクの自分と相手の言語は、会話中も選び直せる。次の発話から効く（Android 版と同じ）。 */
    val canChooseLanguages get() = selection.source == AudioSource.MICROPHONE && phase != AudioPhase.STOPPING
    val listening get() = phase == AudioPhase.STARTING || phase == AudioPhase.LISTENING
    val canStop get() = listening
    val selfOptions get() = selection.languages
    val partnerOptions get() = listOf(null) + selection.languages.filterNot { it.sameAs(selection.self) }
    val playbackFromOptions get() = selection.languages.filterNot { it.sameAs(selection.playbackTo) }
    val playbackToOptions get() = Language.entries.filterNot { it.sameAs(selection.playbackFrom) }

    /** マイクは話した言語で話し手を決めるので、言語の識別ができる認識の設定が要る。 */
    val canStart get() = canConfigure && (selection.source == AudioSource.PLAYBACK || automaticAvailable)
}

internal val Language.speechCode: String get() = if (this == Language.ZH_HANT) "yue" else code

/** UI スレッドでセッションに出来事を渡し、返された処理を接続する。 */
internal class AudioPresenter(
    private val scope: CoroutineScope,
    private val capture: AudioCapture,
    private val loadEngine: suspend (AudioSelection) -> SpeechEngine,
    private val openTranslation: (CoroutineScope, TranslationStream.Options) -> TranslationStream,
    initialSelection: AudioSelection = AudioSelection(),
    private val saveSelection: (AudioSelection) -> Unit = {},
    private val io: CoroutineDispatcher = Dispatchers.IO,
    private val supportsAutomatic: (SpeechModel) -> Boolean = { true },
    private val chooseLog: (String) -> Path? = { null },
    private val copyText: (String) -> Unit = {},
) : AutoCloseable {
    private val mutableState = MutableStateFlow(AudioState(initialSelection, automaticAvailable = supportsAutomatic(initialSelection.recognition.model)))
    val state = mutableState.asStateFlow()

    /**
     * 入力の音量の目安（0〜1）。区切るボタンの輪と話し中のカードのバーに出す。音声のたびに変わるので、
     * 画面の状態（[state]）と分けて、音量を描く部品だけが購読する。
     */
    private val mutableLevel = MutableStateFlow(0f)
    val level = mutableLevel.asStateFlow()
    private val selection get() = state.value.selection
    private var session = selection.session()
    private val translations = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var subscription: Job? = null
    private var engine: SpeechEngine? = null
    private val loading = Mutex()
    private var preparing: Job? = null
    private var visible = false
    private var closed = false
    private var active: CaptureRun? = null
    private var partialJob: Pair<Long, Job>? = null

    private class CaptureRun(val selection: AudioSelection) {
        var input: Job? = null
        var engine: SpeechEngine? = null
    }

    fun selectSource(source: AudioSource) = configure(selection.copy(source = source))

    fun selectSelf(language: Language) {
        if (language in selection.languages) chooseLanguages(selection.copy(self = language,
            partner = selection.partner?.takeUnless { it.sameAs(language) }))
    }

    fun selectPartner(language: Language?) {
        if (language == null || language in selection.languages && !language.sameAs(selection.self)) {
            chooseLanguages(selection.copy(partner = language))
        }
    }

    fun selectPlaybackFrom(language: Language) {
        if (language in selection.languages && !language.sameAs(selection.playbackTo)) configure(selection.copy(playbackFrom = language))
    }

    fun selectPlaybackTo(language: Language) {
        if (!language.sameAs(selection.playbackFrom)) configure(selection.copy(playbackTo = language))
    }

    fun setRecognition(settings: DesktopRecognitionSettings) {
        if (settings != selection.recognition) configure(selection.copy(recognition = settings).validLanguages())
    }

    fun usePreparedRecognition(model: SpeechModel) {
        if (model == selection.recognition.model) {
            mutableState.value = state.value.copy(automaticAvailable = supportsAutomatic(model))
            saveSelection(selection)
        } else {
            setRecognition(selection.recognition.copy(model = model))
        }
    }

    fun swapLanguage(turnId: Long) {
        if (selection.source == AudioSource.MICROPHONE && engine != null) handle(session, Event.LanguageSwapped(turnId))
    }

    fun setVisible(visible: Boolean) {
        this.visible = visible
        releaseIfHidden()
    }

    fun isUsingQwen(): Boolean = active?.selection?.recognition?.model == SpeechModel.QWEN3_ASR

    /**
     * マイクの自分・相手の言語の選び直し。会話中でも中核に知らせるだけで、認識器もセッションも作り直さない。
     * 中核が次の発話から新しい言語で振り分け、相手を選び直したときは自分の発話を訳し直す（docs/設計/会話翻訳.md）。
     * 同じ「自動」を選び直すと、見つけた相手の言語を忘れて探し直す。
     */
    private fun chooseLanguages(updated: AudioSelection) {
        if (!state.value.canChooseLanguages || closed) return
        val previous = selection
        if (updated == previous) {
            if (updated.partner == null) handle(session, Event.ModeChanged(PartnerMode.AUTO))
            return
        }
        mutableState.value = state.value.copy(selection = updated)
        if (updated.self != previous.self) handle(session, Event.SelfChanged(updated.self))
        if (updated.partner != previous.partner) handle(session, Event.ModeChanged(PartnerMode(updated.partner)))
        saveSelection(updated)
    }

    /** 音の出どころ・認識の設定・再生音の言語。止めているときだけ変え、認識器とセッションを作り直す。 */
    private fun configure(updated: AudioSelection, reload: Boolean = false) {
        if (!state.value.canConfigure || closed || (!reload && updated == selection)) return
        releaseEngine()
        session = updated.session()
        partialJob = null
        translations.coroutineContext.cancelChildren()
        mutableState.value = AudioState(updated, automaticAvailable = supportsAutomatic(updated.recognition.model))
        saveSelection(updated)
    }

    fun showSubtitle(visible: Boolean) {
        if (selection.source == AudioSource.PLAYBACK) mutableState.value = state.value.copy(subtitleVisible = visible)
    }

    fun resetRecognitionBackend() = configure(selection, reload = true)

    fun start() {
        if (!state.value.canStart || closed) return
        mutableState.value = state.value.copy(
            phase = AudioPhase.STARTING, error = null, subtitleVisible = selection.source == AudioSource.PLAYBACK,
        )
        handle(session, Event.RecognitionBusy(false))
        handle(session, Event.Started)
    }

    fun stop() {
        if (!state.value.canStop || closed) return
        mutableState.value = state.value.copy(phase = AudioPhase.STOPPING)
        handle(session, Event.StopRequested)
    }

    fun cut() {
        if (state.value.canCut) active?.engine?.cut()
    }

    fun clear() = handle(session, Event.Cleared)

    fun exportLog() = withLog { turns ->
        val file = chooseLog(DesktopConversationLog.fileName(turns)) ?: return@withLog
        val text = DesktopConversationLog.format(turns)
        scope.launch {
            try {
                withContext(io) { Files.writeString(file, text) }
                mutableState.value = state.value.copy(notice = "会話ログを保存しました。", error = null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logs.w("会話ログを保存できませんでした。", error)
                mutableState.value = state.value.copy(error = "会話ログを保存できませんでした。", notice = null)
            }
        }
    }

    fun copyLog() = withLog { turns ->
        try {
            copyText(DesktopConversationLog.format(turns))
            mutableState.value = state.value.copy(notice = "会話ログをコピーしました。", error = null)
        } catch (error: Exception) {
            Logs.w("会話ログをコピーできませんでした。", error)
            mutableState.value = state.value.copy(error = "会話ログをコピーできませんでした。", notice = null)
        }
    }

    private inline fun withLog(action: (List<Turn>) -> Unit) {
        if (state.value.canExport) action(session.turns)
    }

    /** 画面の表示中に準備を始める。入力開始後は画面を離れても同じ読み込みを続ける。 */
    fun prepare() {
        if (!visible || !state.value.canStart || closed || engine != null || preparing?.isActive == true) return
        val current = session
        preparing = scope.launch {
            try {
                obtainEngine()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                if (visible && !closed && current === session) {
                    mutableState.value = state.value.copy(error = error.message ?: "音声認識を準備できません。")
                }
            }
        }
    }

    private fun releaseIfHidden() {
        if (closed || visible || active != null) return
        val hadEngine = engine != null
        releaseEngine()
        if (hadEngine) handle(session, Event.RecognitionReleased)
    }

    private fun releaseEngine() {
        preparing?.cancel()
        preparing = null
        subscription?.cancel()
        subscription = null
        val previous = engine
        engine = null
        previous?.close()
    }

    private suspend fun obtainEngine(): SpeechEngine = loading.withLock {
        engine?.let { return@withLock it }
        val current = session
        val requested = selection
        var loaded: SpeechEngine? = null
        try {
            val asr = withContext(io) { loadEngine(requested).also { loaded = it } }
            if (closed || current !== session) throw CancellationException("認識の設定が変わりました")
            engine = asr
            subscription = scope.launch(start = CoroutineStart.UNDISPATCHED) {
                launch(start = CoroutineStart.UNDISPATCHED) {
                    asr.load.collect { load ->
                        if (active != null && session === current) {
                            mutableState.value = state.value.copy(lagSeconds = load.behindSeconds)
                            handle(current, Event.RecognitionBusy(load.urgent))
                        }
                    }
                }
                asr.events.collect { event ->
                    val retained = event is SpeechEvent.Retranscribed || event is SpeechEvent.RetranscribeFailed || event is SpeechEvent.Released
                    if (session === current && (active != null || retained)) handle(current, Event.Speech(event, active?.selection?.spoken ?: selection.spoken))
                }
            }
            asr
        } catch (error: LinkageError) {
            Logs.w("Windows の音声ライブラリを読み込めませんでした", error)
            throw IllegalStateException("音声ライブラリを読み込めません。DLL の配置を確認して再起動してください。", error)
        } finally {
            // JNI の読み込み中に画面を離れた場合も、作成済みの認識器を解放する。
            loaded?.takeUnless { it === engine }?.close()
        }
    }

    private fun begin() {
        val run = CaptureRun(selection)
        active = run
        run.input = scope.launch {
            try {
                val asr = obtainEngine()
                run.engine = asr
                val current = session
                asr.languages = languagesFor(current, run.selection)
                asr.reset()
                capture.capture(run.selection.source, started = {
                    scope.launch {
                        if (active === run && state.value.phase == AudioPhase.STARTING) {
                            mutableState.value = state.value.copy(phase = AudioPhase.LISTENING)
                        }
                    }
                }) { samples ->
                    asr.accept(samples)
                    mutableLevel.value = level(samples)
                }
                if (active === run) stop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failInput(run, error.message ?: "音声入力を開始できません。")
            } catch (error: LinkageError) {
                Logs.w("Windows の音声ライブラリを読み込めませんでした", error)
                failInput(run, "音声ライブラリを読み込めません。DLL の配置を確認して再起動してください。")
            }
        }
    }

    private fun failInput(run: CaptureRun, message: String) {
        if (active !== run || !state.value.canStop) return
        mutableState.value = state.value.copy(error = message)
        stop()
    }

    private fun flush() {
        val run = checkNotNull(active)
        scope.launch {
            run.input?.cancelAndJoin()
            if (active !== run) return@launch
            run.engine?.flush() ?: handle(session, Event.Speech(SpeechEvent.Flushed))
        }
    }

    private fun handle(current: ConversationSession, event: Event) {
        if (closed || current !== session) return
        val effects = current.handle(event)
        active?.let { run -> run.engine?.languages = languagesFor(current, run.selection) }
        publish(current)
        for (effect in effects) when (effect) {
            Effect.Begin -> begin()
            Effect.Flush -> flush()
            Effect.Finish -> {
                active = null
                mutableLevel.value = 0f
                mutableState.value = state.value.copy(phase = AudioPhase.IDLE, lagSeconds = 0f)
                releaseIfHidden()
            }
            is Effect.TranslateFinal -> translations.launch { translateFinal(current, effect) }
            is Effect.TranslatePartial -> translatePartial(current, effect)
            is Effect.CancelPartial -> cancelPartial(current, effect.requestId)
            is Effect.Retranscribe -> engine?.retranscribe(effect.utterance, effect.language)
        }
    }

    private fun languagesFor(current: ConversationSession, selection: AudioSelection): SpeechLanguages {
        val language = selection.spoken ?: return current.speechLanguages
        return SpeechLanguages(separateChinese = language.isChinese, specified = selection.specifiedCode)
    }

    private fun publish(current: ConversationSession) {
        mutableState.value = state.value.copy(
            live = current.listeningCards, turns = current.turns.takeLast(MAX_SHOWN_TURNS), canCut = current.canCut,
            partner = current.partner, partnerFound = current.partnerFound,
            canExport = selection.source == AudioSource.MICROPHONE && current.turns.isNotEmpty(),
            subtitle = if (selection.source == AudioSource.PLAYBACK) playbackSubtitle(current) else null,
        )
    }

    private fun translatePartial(current: ConversationSession, effect: Effect.TranslatePartial) {
        val job = translations.launch(start = CoroutineStart.LAZY) {
            var text: String? = null
            try {
                val result = translate(effect.text, TranslationStream.Options(effect.source, effect.target, maxTokens = 160))
                text = (result as? SegmentUpdate.Final)?.text
            } finally {
                if (current === session && partialJob?.first == effect.requestId) {
                    partialJob = null
                    handle(current, Event.PartialDone(effect.requestId, text))
                }
            }
        }
        partialJob = effect.requestId to job
        job.start()
    }

    private fun cancelPartial(current: ConversationSession, requestId: Long) {
        val job = partialJob?.takeIf { it.first == requestId }?.second ?: return
        partialJob = null
        job.cancel()
        // 実行前に取り消すと本体の finally は動かないため、ここで中核に完了を返す。
        handle(current, Event.PartialDone(requestId, null))
    }

    private suspend fun translateFinal(current: ConversationSession, effect: Effect.TranslateFinal) {
        val turn = effect.turn
        var done = Event.FinalDone(turn.id, "", 0, failure = "翻訳が中断されました。")
        try {
            val result = translate(turn.original, TranslationStream.Options(turn.direction.source, effect.target)) {
                handle(current, Event.FinalProgress(turn.id, it))
            }
            done = when (result) {
                is SegmentUpdate.Final -> {
                    Logs.text { "音声翻訳: ${turn.original.take(4000)} → ${result.text.take(4000)}" }
                    Event.FinalDone(turn.id, result.text, result.millis)
                }
                is SegmentUpdate.Failed -> done.copy(failure = result.message)
                is SegmentUpdate.Partial -> done
            }
        } finally {
            handle(current, done)
        }
    }

    private suspend fun translate(
        text: String,
        options: TranslationStream.Options,
        progress: (String) -> Unit = {},
    ): SegmentUpdate = coroutineScope {
        val stream = openTranslation(this, options)
        try {
            stream.translateOne(text, progress)
        } finally {
            stream.close()
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        active?.input?.cancel()
        active = null
        translations.cancel()
        releaseEngine()
    }
}

/** 画面に出す確定したカードの数。会話の記録（保存・コピー）はセッションの全部を使う。 */
private const val MAX_SHOWN_TURNS = 200

/** 音量の目安。-55dBFS（静かな部屋）〜-15dBFS（近くで話す）を0〜1に割り当てる（Android 版と同じ）。 */
internal fun level(samples: FloatArray): Float {
    if (samples.isEmpty()) return 0f
    var sum = 0.0
    for (s in samples) sum += s * s
    val db = 10 * log10(sum / samples.size + 1e-10)
    return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
}

internal fun AudioSelection.validLanguages(): AudioSelection {
    val mine = self.takeIf { it in languages } ?: Language.JA
    val from = playbackFrom.takeIf { it in languages } ?: Language.EN
    val other = partner?.takeIf { it in languages && !it.sameAs(mine) }
    return copy(self = mine, partner = other, playbackFrom = from,
        playbackTo = playbackTo.takeUnless { it.sameAs(from) } ?: Language.JA)
}
