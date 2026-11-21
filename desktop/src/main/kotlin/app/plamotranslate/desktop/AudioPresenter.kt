package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.llm.sameAs
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.conversation.SwapState
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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

internal enum class AudioPhase(val label: String) {
    IDLE("停止中"), STARTING("音声入力を準備中…"), LISTENING("聞き取り中…"), STOPPING("話していた分を確定中…"),
}

internal data class AudioSelection(
    val source: AudioSource = AudioSource.MICROPHONE,
    val self: Language = Language.JA,
    val partner: Language? = null,
    val playbackFrom: Language = Language.EN,
    val playbackTo: Language = Language.JA,
    val partnerSpeaking: Boolean? = null,
    val faceToFace: Boolean = false,
    val recognition: DesktopRecognitionSettings = DesktopRecognitionSettings(),
) {
    val languages get() = recognition.model.languages

    val spoken: Language?
        get() = when (source) {
            AudioSource.MICROPHONE -> when (partnerSpeaking) {
                null -> null
                true -> partner
                false -> self
            }
            AudioSource.PLAYBACK -> playbackFrom
        }

    fun session(): ConversationSession = when (source) {
        AudioSource.MICROPHONE -> ConversationSession(PartnerMode(partner), self, languages)
        AudioSource.PLAYBACK -> ConversationSession(PartnerMode(playbackFrom), playbackTo, languages)
    }
}

internal data class AudioCard(
    val key: String,
    val language: Language?,
    val original: String,
    val translation: String,
    val status: String,
    val error: String? = null,
    val mine: Boolean? = null,
    val turnId: Long? = null,
    val swap: SwapState = SwapState.UNAVAILABLE,
)

internal data class AudioState(
    val selection: AudioSelection,
    val phase: AudioPhase = AudioPhase.IDLE,
    val cards: List<AudioCard> = emptyList(),
    val subtitle: SubtitleLine? = null,
    val subtitleVisible: Boolean = false,
    val canCut: Boolean = false,
    val error: String? = null,
    val notice: String? = null,
    val detectedPartner: Language? = null,
    val canExport: Boolean = false,
    val automaticAvailable: Boolean = true,
) {
    val canConfigure get() = phase == AudioPhase.IDLE
    val canStop get() = phase == AudioPhase.STARTING || phase == AudioPhase.LISTENING
    val selfOptions get() = selection.languages
    val partnerOptions get() = listOf(null) + selection.languages.filterNot { it.sameAs(selection.self) }
    val playbackFromOptions get() = selection.languages.filterNot { it.sameAs(selection.playbackTo) }
    val playbackToOptions get() = Language.entries.filterNot { it.sameAs(selection.playbackFrom) }
    val canStart get() = canConfigure && (selection.source == AudioSource.PLAYBACK || selection.partnerSpeaking != null || automaticAvailable)
}

/** UI スレッドでセッションに出来事を渡し、返された処理を接続する。 */
internal class AudioPresenter(
    private val scope: CoroutineScope,
    private val capture: AudioCapture,
    private val loadEngine: (DesktopRecognitionSettings) -> SpeechEngine,
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
    private val selection get() = state.value.selection
    private var session = selection.session()
    private val translations = CoroutineScope(scope.coroutineContext + SupervisorJob(scope.coroutineContext[Job]))
    private var engine: SpeechEngine? = null
    private var closed = false
    private var active: CaptureRun? = null
    private var partialJob: Pair<Long, Job>? = null

    private class CaptureRun(val selection: AudioSelection) {
        var input: Job? = null
        var engine: SpeechEngine? = null
    }

    fun selectSource(source: AudioSource) = configure(selection.copy(source = source))

    fun selectSelf(language: Language) {
        if (language in selection.languages) configure(selection.copy(self = language,
            partner = selection.partner?.takeUnless { it.sameAs(language) }))
    }

    fun selectPartner(language: Language?) {
        if (language == null || language in selection.languages && !language.sameAs(selection.self)) {
            configure(selection.copy(partner = language, partnerSpeaking = selection.partnerSpeaking.takeUnless { it == true && language == null }))
        }
    }

    fun selectPlaybackFrom(language: Language) {
        if (language in selection.languages && !language.sameAs(selection.playbackTo)) configure(selection.copy(playbackFrom = language))
    }

    fun selectPlaybackTo(language: Language) {
        if (!language.sameAs(selection.playbackFrom)) configure(selection.copy(playbackTo = language))
    }

    fun selectSpeaker(partner: Boolean?) {
        if (partner == true && selection.partner == null) return
        if (!state.value.canConfigure) return
        mutableState.value = state.value.copy(selection = selection.copy(partnerSpeaking = partner))
        saveSelection(selection)
    }

    fun setFaceToFace(enabled: Boolean) {
        mutableState.value = state.value.copy(selection = selection.copy(faceToFace = enabled))
        saveSelection(selection)
    }

    fun setRecognition(settings: DesktopRecognitionSettings) {
        if (settings != selection.recognition) configure(selection.copy(recognition = settings).validLanguages())
    }

    fun swapLanguage(turnId: Long) {
        if (selection.source == AudioSource.MICROPHONE && engine != null) handle(session, Event.LanguageSwapped(turnId))
    }

    private fun configure(updated: AudioSelection) {
        if (!state.value.canConfigure || closed) return
        val previous = selection
        if (updated == previous) {
            if (updated.source == AudioSource.MICROPHONE && updated.partner == null) handle(session, Event.ModeChanged(PartnerMode.AUTO))
            return
        }
        if (updated.recognition != previous.recognition || updated.source != previous.source || updated.source == AudioSource.PLAYBACK) {
            engine?.close()
            engine = null
            session = updated.session()
            partialJob = null
            translations.coroutineContext.cancelChildren()
            mutableState.value = AudioState(updated, automaticAvailable = supportsAutomatic(updated.recognition.model))
        } else {
            mutableState.value = state.value.copy(selection = updated)
            if (updated.self != previous.self) handle(session, Event.SelfChanged(updated.self))
            if (updated.partner != previous.partner) handle(session, Event.ModeChanged(PartnerMode(updated.partner)))
        }
        saveSelection(updated)
    }

    fun showSubtitle(visible: Boolean) {
        if (selection.source == AudioSource.PLAYBACK) mutableState.value = state.value.copy(subtitleVisible = visible)
    }

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

    private fun begin() {
        val run = CaptureRun(selection)
        active = run
        run.input = scope.launch {
            var loaded: SpeechEngine? = null
            try {
                val asr = engine ?: withContext(io) { loadEngine(run.selection.recognition).also { loaded = it } }.also { engine = it }
                run.engine = asr
                val current = session
                val language = run.selection.spoken
                asr.languages = languagesFor(current, run.selection)
                asr.listener = { event ->
                    scope.launch {
                        val retained = event is SpeechEvent.Retranscribed || event is SpeechEvent.RetranscribeFailed || event is SpeechEvent.Released
                        if (engine === asr && (active === run || retained)) handle(current, Event.Speech(event, language))
                    }
                }
                asr.loadListener = { load ->
                    scope.launch { if (active === run) handle(session, Event.RecognitionBusy(load.urgent)) }
                }
                asr.reset()
                capture.capture(run.selection.source, started = {
                    scope.launch {
                        if (active === run && state.value.phase == AudioPhase.STARTING) {
                            mutableState.value = state.value.copy(phase = AudioPhase.LISTENING)
                        }
                    }
                }, asr::accept)
                if (active === run) stop()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                failInput(run, error.message ?: "音声入力を開始できません。")
            } catch (error: LinkageError) {
                Logs.w("Windows の音声ライブラリを読み込めませんでした", error)
                failInput(run, "音声ライブラリを読み込めません。DLL の配置を確認して再起動してください。")
            } finally {
                // 読み込みの完了と停止が重なったとき、受け取れなかった認識器も閉じる。
                loaded?.takeUnless { it === engine }?.close()
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
                mutableState.value = state.value.copy(phase = AudioPhase.IDLE)
            }
            is Effect.TranslateFinal -> translations.launch { translateFinal(current, effect) }
            is Effect.TranslatePartial -> translatePartial(current, effect)
            is Effect.CancelPartial -> cancelPartial(current, effect.requestId)
            is Effect.Retranscribe -> engine?.retranscribe(effect.utterance, effect.language)
        }
    }

    private fun languagesFor(current: ConversationSession, selection: AudioSelection): SpeechLanguages {
        val language = selection.spoken ?: return current.speechLanguages
        val code = if (language == Language.ZH_HANT) "yue" else language.code
        return SpeechLanguages(separateChinese = language.isChinese, specified = code)
    }

    private fun publish(current: ConversationSession) {
        val live = current.listeningCards.map { card ->
            AudioCard("live-${card.card}", card.direction?.source, card.text, card.translation,
                if (card.open) "途中" else "書き起こしを確定中", mine = card.direction?.mine)
        }
        val final = current.turns.takeLast(100).map { turn ->
            AudioCard("final-${turn.id}", turn.direction.source, turn.original, turn.translation,
                when {
                    turn.swap == SwapState.REWRITING -> "書き起こし直し中"
                    turn.direction.target == null -> "相手の言語が分かったら訳します"
                    turn.translated -> "確定"
                    else -> "翻訳中"
                }, turn.failure, turn.direction.mine, turn.id, turn.swap)
        }
        mutableState.value = state.value.copy(
            cards = live.asReversed() + final.asReversed(), canCut = current.canCut,
            detectedPartner = current.partner.takeIf { current.partnerFound },
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
                is SegmentUpdate.Final -> Event.FinalDone(turn.id, result.text, result.millis)
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
        engine?.close()
    }
}

internal fun AudioSelection.validLanguages(): AudioSelection {
    val mine = self.takeIf { it in languages } ?: Language.JA
    val from = playbackFrom.takeIf { it in languages } ?: Language.EN
    val other = partner?.takeIf { it in languages && !it.sameAs(mine) }
    return copy(self = mine, partner = other, playbackFrom = from,
        playbackTo = playbackTo.takeUnless { it.sameAs(from) } ?: Language.JA,
        partnerSpeaking = partnerSpeaking.takeUnless { it == true && other == null })
}
