package app.plamotranslate.conversation

import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.models.TranslationModel
import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.plamotranslate.EngineHolder
import app.plamotranslate.ReadAloud
import app.plamotranslate.AdvancedPreferences
import app.plamotranslate.RecognitionPreferences
import app.plamotranslate.TranslationPreferences
import app.plamotranslate.loadSpeechEngine
import app.plamotranslate.asr.WavFile
import app.plamotranslate.audio.MicRecorder
import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.conversation.Turn
import app.plamotranslate.pipeline.speech.RetranscribeFailure
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.UtteranceRetention
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import app.plamotranslate.translation.Translations
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import app.plamotranslate.llm.TranslationEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 音声認識モデルの状態。 */
sealed interface AsrState {
    data object Loading : AsrState
    data object Ready : AsrState
    data class Failed(val message: String) : AsrState
}

/** 画面の並べ方。対面は上下2分割で上半分を相手向けに回す。1画面は2人で同じ一覧を見る。 */
enum class ViewMode(val label: String) { FACE_TO_FACE("対面"), SHARED("1画面") }

data class ConversationUiState(
    val asr: AsrState = AsrState.Loading,
    val listening: Boolean = false,
    val mode: PartnerMode = PartnerMode.AUTO,
    val viewMode: ViewMode = ViewMode.FACE_TO_FACE,
    /** 自分の言語。 */
    val self: Language = Language.JA,
    /** 今、自分の発話を訳す先の言語。自動でまだ相手が話していなければ null。 */
    val partner: Language? = null,
    /** 相手の言語を自動で見つけたか（「自動で判定」と出す）。 */
    val partnerFound: Boolean = false,
    val turns: List<Turn> = emptyList(),
    /** マイクの音量（0〜1）。聞いている間だけ動く。 */
    val level: Float = 0f,
    /** 音声認識が録音からどれだけ遅れているか（秒。認識を待っている音声の長さ）。 */
    val lagSeconds: Float = 0f,
    /** 聞き取り中のカード（話し中と書き起こし待ち。音声認識のカードの順）。 */
    val cards: List<LiveSpeech> = emptyList(),
    /** 区切るボタンを押せるか（聞いていて、話し中のカードがある）。 */
    val canCut: Boolean = false,
    val canStart: Boolean = false,
    val awaitingInput: Boolean = false,
    val error: String? = null,
    /** 会話で選べる言語（設定で選んだ音声認識モデルの言語）。 */
    val languages: List<Language> = SpeechModel.DEFAULT.languages,
)

/**
 * 会話翻訳のアダプター（docs/設計/会話翻訳.md「全体の流れ」）。マイクの音声を音声認識のスレッドで [SpeechEngine] に流し、
 * 届いた出来事を中核の [ConversationSession] に渡して、返ってきた作業（確定訳・途中訳・途中訳の取り消し）を翻訳のストリームで行う。
 * 何をいつ訳すか、方向、カードと聞き取り中の表示は [ConversationSession] が決める。翻訳モデルは [EngineHolder] のものを使う。
 * [ConversationSession] は画面のスレッドだけから呼ぶ。
 */
class ConversationViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()
    val model: StateFlow<ModelState<TranslationModel>> = EngineHolder.state

    /** 表示モードと自分の言語は次に開いたときも同じにする。init より前に置く（init で読む）。 */
    private val preferences = ConversationPreferences(app)

    /** 音声認識モデル（設定の画面で前もって選ぶ）。会話で選べる言語はこのモデルの言語。画面を開いたときに読む。 */
    private val recognition = RecognitionPreferences(app)
    private val speechModel = recognition.model

    private val session = ConversationSession(self = preferences.self(speechModel.languages) ?: Language.JA, heard = speechModel.languages)

    private var asr: SpeechEngine? = null

    /** 音声を音声認識へ渡している作業（マイクか音声ファイル）。止めるときは、これを止めてから確定させる。 */
    private var inputJob: Job? = null

    /** 聞き始める作業（[Effect.Begin]）で流す音声の入力（マイクか音声ファイル）。 */
    private var pendingInput: (suspend (SpeechEngine) -> Unit)? = null

    /** 聞いている間だけ変えた翻訳の CPU のスレッド数（0 なら変えていない）。 */
    private var translateThreads = 0

    /** 画面を閉じた後にも終える後始末（翻訳のスレッド数を戻す）。エンジンは画面より長く生きるので、viewModelScope の外で行う。 */
    private val releaseScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 走っている途中訳。確定訳が来たら取り消す。 */
    private val partialJobs = mutableMapOf<Long, Job>()

    init {
        preferences.viewMode?.let { mode -> _state.update { it.copy(viewMode = mode) } }
        _state.update { it.copy(languages = speechModel.languages) }
        requestStart()
        viewModelScope.launch { model.collect { updateModels() } }
        EngineHolder.ensureLoaded(app)
        loadAsr()
    }

    fun loadAsr() {
        _state.update { it.copy(asr = AsrState.Loading) }
        updateModels()
        viewModelScope.launch {
            val begin = System.nanoTime()
            val result = runCatching {
                loadSpeechEngine(app, speechModel, recognition.profile(PROFILE), recognition.asrTuning, UtteranceRetention.KEEP_UTTERANCES)
            }
            result.onSuccess {
                log("音声認識を読み込みました: $speechModel ${(System.nanoTime() - begin) / 1_000_000}ms")
                asr = it.also(::attach)
            }
            _state.update {
                it.copy(asr = result.fold({ AsrState.Ready }, { e -> AsrState.Failed(e.message ?: e.toString()) }))
            }
            updateModels()
        }
    }

    private fun updateModels() = handle(Event.ModelsChanged(_state.value.asr == AsrState.Ready && model.value is ModelState.Ready))

    fun requestStart() = handle(Event.StartRequested)

    fun setMode(mode: PartnerMode) = handle(Event.ModeChanged(mode))

    /** 自分の言語を変える。相手が同じ言語なら、相手は自動に戻る。 */
    fun setSelf(self: Language) {
        preferences.saveSelf(self)
        handle(Event.SelfChanged(self))
    }

    fun setViewMode(mode: ViewMode) {
        preferences.viewMode = mode
        _state.update { it.copy(viewMode = mode) }
    }

    /**
     * 音声認識の出来事と混み具合を受け取る。出来事はセグメンターのスレッドから届くので、画面のスレッドの制御へ順に渡す。
     * 認識が確定に要るジョブ（書き起こしと文の話し手の判定）を抱えている間は、途中訳を待たせる（CPU を確定の書き起こしに回す）。
     */
    private fun attach(engine: SpeechEngine) {
        engine.languages = session.speechLanguages
        viewModelScope.launch(start = CoroutineStart.UNDISPATCHED) { engine.events.collect { onEvent(Event.Speech(it)) } }
        viewModelScope.launch {
            engine.load.collect { load ->
                if (load.behindSeconds != _state.value.lagSeconds) _state.update { it.copy(lagSeconds = load.behindSeconds) }
                handle(Event.RecognitionBusy(load.urgent))
            }
        }
    }

    /**
     * マイクで聞く。録音の側で音量を測って画面に出し、音声は音声認識に渡すだけにする（音声認識はセグメンターと認識のワーカーの
     * スレッドで動き、録音を待たせない）。書き起こしが重いモデル（1回に1〜2秒）でも、音量のバーは止まらず、音声も落とさない。
     * 認識の遅れ（まだ書き起こしていない確定に要る音声が、どれだけ前のものか）は画面に出す。
     *
     * 区切るボタンは、音声と同じ順に区切りの命令を渡す（[cut]）。押した時点の音声の位置で区切られる。
     * 停止は録音だけを止め、たまった音声を書き起こしてから終わる（[stop]）。
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = listen { engine ->
        val recording = recorder()
        try {
            MicRecorder.stream().collect { raw ->
                val chunk = muteReadAloud(raw)
                meter(chunk)
                engine.accept(chunk)
                recording?.write(chunk)
            }
        } finally {
            recording?.close()
        }
    }

    /**
     * 詳しい記録を残すとき（デバッグ版の既定。正式版は詳細設定で本人が選ぶ）だけ、音声認識へ渡した音声を WAV に書く
     * （docs/設計/会話翻訳.md「動作確認」）。[startFromWav] でリプレイして、区切りと確定の文を前後で比べるため。
     * 会話の中身なので、端末の外部ファイルの `asr-rec/` にだけ置き、調べ終えたら消す。
     */
    private fun recorder(): WavFile.Writer? {
        if (!AdvancedPreferences(app).detailedRecords) return null
        val dir = app.getExternalFilesDir(RECORDING_DIR) ?: return null
        val file = File(dir, "conv_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())}.wav")
        log("会話の音声を書く: ${file.path}")
        return runCatching { WavFile.Writer(file) }.onFailure { Logs.w("会話: 音声を書けない", it) }.getOrNull()
    }

    /**
     * 動作確認用：マイクの代わりに音声ファイルを流す（デバッグ版で intent の wav に渡す）。
     * 途中結果の出方も確かめられるよう、実際の話す速さで流す。
     */
    fun startFromWav(path: String) = listen { engine ->
        val samples = WavFile.read(File(path))
        val chunk = SpeechEngine.SAMPLE_RATE / 10
        // 末尾に、発話の終わりとみなす無音より長い無音を足し、話し終わりをVADに検出させる
        val silence = ((PROFILE.endSilenceSeconds + 1) * SpeechEngine.SAMPLE_RATE).toInt()
        val padded = samples + FloatArray(silence)
        for (from in padded.indices step chunk) {
            val begin = System.nanoTime()
            val part = muteReadAloud(padded.copyOfRange(from, minOf(from + chunk, padded.size)))
            meter(part)
            engine.accept(part)
            val spentMs = (System.nanoTime() - begin) / 1_000_000
            // 止めたら（取り消されたら）流すのをやめる
            delay((100 - spentMs).coerceAtLeast(0))
        }
        log("音声ファイルの認識が終わりました: ${File(path).name}")
    }

    /**
     * 訳文を読み上げている間とその直後は、マイクの音を無音に置き換える。読み上げの声を発話として拾わないため（2026-11-08）。
     * 録音した時点で判定する（認識が遅れていても、読み上げと重なった音声だけを消す）。
     */
    private fun muteReadAloud(raw: FloatArray): FloatArray = if (ReadAloud.audible.value) FloatArray(raw.size) else raw

    /** 音量を画面に出す。音声認識のスレッドでは呼ばない（書き起こしの間に止まるため）。 */
    private fun meter(chunk: FloatArray) = _state.update { it.copy(level = level(chunk)) }

    /**
     * 聞くのを止める。録音だけを止め、たまった音声を書き起こしてから終わる（認識が遅れていても、止める前に話した分を落とさない）。
     * 止める順番は制御が決める（[Event.StopRequested]）。
     */
    fun stop() = handle(Event.StopRequested)

    /**
     * 今話している分を確定して次のカードにする（聞くのは続ける）。音声認識は押した時点の音声の位置で話し中のカードを閉じ、
     * 閉じたカードは書き起こし待ちになる。話し中のカードが無ければ押せない（[ConversationUiState.canCut]）。
     */
    fun cut() {
        if (session.canCut) asr?.cut()
    }

    /** 確定したカードの言語の取り違えを直す。もう一方の話し手の言語として書き起こし直し、訳し直す（[Event.LanguageSwapped]）。 */
    fun swapLanguage(turnId: Long) = handle(Event.LanguageSwapped(turnId))

    fun clear() {
        handle(Event.Cleared)
        _state.update { it.copy(error = null) }
    }

    /**
     * 聞く。始める順番は制御が決め、聞き始める作業（[Effect.Begin]）で [begin] が [block] を流す。
     */
    private fun listen(block: suspend (SpeechEngine) -> Unit) {
        updateModels()
        if (!session.awaitingInput) return
        pendingInput = block
        _state.update { it.copy(error = null) }
        handle(Event.InputAvailable)
    }

    /**
     * 聞き始める（[Effect.Begin]）。聞いている間だけ、翻訳の CPU のスレッド数を詳細設定の値にし（音声認識と翻訳の CPU の取り合いを
     * 測るため。戻すのは [Effect.Finish]）、音声認識を始め直してから音声を流す。[block] が音声を渡し終えたら（音声ファイルの終わり・
     * 入力の失敗）止める（[Event.StopRequested]）。停止ボタンで止めたときは、[Effect.Flush] がこの作業を止めてから確定させる。
     */
    private fun begin() {
        val engine = asr ?: return
        val block = pendingInput ?: return
        pendingInput = null
        inputJob = viewModelScope.launch {
            try {
                translateThreads = recognition.translateThreads
                if (translateThreads > 0) configureTranslation(translateThreads)
                engine.reset()
                withContext(Dispatchers.IO) { block(engine) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                // 停止ボタンで止めたとき・画面を閉じたときは、もう止めている（制御は2回目を無視する）
                handle(Event.StopRequested)
            }
        }
    }

    /**
     * 聞き終えた（[Effect.Finish]）。音声の入力が残っていれば止め、聞いている間だけ変えた翻訳のスレッド数を戻す。画面を閉じたとき
     * （[Event.Closed]）にも届くので、戻す処理は viewModelScope の外（[releaseScope]）で行う（エンジンは画面より長く生きる）。
     */
    private fun finish() {
        inputJob?.cancel()
        inputJob = null
        _state.update { it.copy(level = 0f, lagSeconds = 0f) }
        val threads = translateThreads
        translateThreads = 0
        if (threads > 0) releaseScope.launch { configureTranslation(0) }
    }

    /**
     * 翻訳の CPU のスレッド数を変える（0 なら設定の画面の値か既定に戻す）。エンジンの設定なので、翻訳のストリームを通さずエンジンを直接使う
     * （docs/設計/翻訳コア.md「ストリームを通さない呼び出し」）。
     */
    private suspend fun configureTranslation(threads: Int) {
        if (model.value !is ModelState.Ready) return
        val engine = EngineHolder.awaitEngine(app)?.first ?: return
        val cpu = TranslationPreferences(app).cpuOptions
        val restore = if (cpu.threads > 0) cpu.threads else TranslationEngine.defaultThreads()
        engine.configure(cpu.boost, if (threads > 0) threads else restore)
        log("翻訳のスレッド数: ${if (threads > 0) threads else restore}")
    }

    /** 音声認識のスレッドから、出来事を画面のスレッドの制御へ順に渡す（画面のスレッドは届いた順に処理する）。 */
    @JvmName("postSpeech")
    private fun post(events: List<SpeechEvent>) = post(events.map(Event::Speech))

    private fun post(events: List<Event>) {
        if (events.isEmpty()) return
        viewModelScope.launch { events.forEach { onEvent(it) } }
    }

    private fun onEvent(event: Event) {
        when (val speech = (event as? Event.Speech)?.event) {
            is SpeechEvent.CardText -> logText { "途中 [${speech.lang}] ${speech.text}" }
            is SpeechEvent.CardFinished -> speech.utterance?.let { u -> logText { "認識 [${u.lang}] ${u.text} 認識${u.recognizeMs}ms" } }
            is SpeechEvent.Retranscribed -> speech.utterance.let { u -> logText { "書き起こし直し 発話${u.id} [${u.lang}] ${u.text} ${u.recognizeMs}ms" } }
            is SpeechEvent.RetranscribeFailed -> log("書き起こし直せなかった 発話${speech.id} ${speech.reason}")
            else -> Unit
        }
        handle(event)
    }

    /** 制御に出来事を渡し、返ってきた作業を行い、画面の状態をそろえる。画面のスレッドから呼ぶ。 */
    private fun handle(event: Event) {
        val effects = session.handle(event)
        sync()
        for (effect in effects) when (effect) {
            is Effect.TranslateFinal -> viewModelScope.launch { translate(effect.turn, effect.target) }
            is Effect.TranslatePartial -> {
                // 登録してから始める（画面のスレッドで始めると、その場で終わって後始末が登録より先に走ることがある）
                val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                    translatePartial(effect.requestId, effect.text, effect.source, effect.target)
                }
                partialJobs[effect.requestId] = job
                job.start()
            }
            is Effect.CancelPartial -> partialJobs[effect.requestId]?.cancel()
            // 音声認識が無ければ書き起こし直せない（カードはそのまま）
            is Effect.Retranscribe -> asr?.retranscribe(effect.utterance, effect.language)
                ?: post(listOf(SpeechEvent.RetranscribeFailed(effect.utterance, RetranscribeFailure.UNAVAILABLE)))
            // 音声の入力を止めてから確定させる（後に渡した音声は次のカードになる）。音声認識が無ければ止め終わりをその場で返す
            Effect.Flush -> {
                val input = inputJob
                viewModelScope.launch {
                    input?.cancelAndJoin()
                    asr?.flush() ?: post(listOf(SpeechEvent.Flushed))
                }
            }
            Effect.Begin -> begin()
            Effect.Finish -> finish()
        }
    }

    private fun sync() {
        // 音声認識が話し手の交代を見る言語は、会話の言語（自分か相手が中国語のときだけ、中国語と日本語を分ける）
        asr?.languages = session.speechLanguages
        _state.update {
            it.copy(
                canStart = session.canStart, awaitingInput = session.awaitingInput,
                turns = session.turns, cards = session.listeningCards, listening = session.listening, canCut = session.canCut,
                self = session.self, mode = session.mode, partner = session.partner, partnerFound = session.partnerFound,
            )
        }
    }

    /** 途中の文字を訳す。終わったら（取り消されても）制御に知らせる。 */
    private suspend fun translatePartial(requestId: Long, text: String, source: Language, target: Language) {
        var result: String? = null
        try {
            if (model.value !is ModelState.Ready) return
            val start = System.nanoTime()
            // 翻訳のストリームを1回ごとに開き、終わったら閉じる（docs/設計/翻訳コア.md「翻訳の契約」）。
            // 確定訳が来たら制御がこのコルーチンを取り消すので、ストリームも閉じて途中訳の生成を止める
            val update = coroutineScope {
                val stream = Translations.open(
                    this, app, TranslationStream.Options(source, target, maxTokens = PARTIAL_MAX_TOKENS),
                )
                try {
                    stream.translateOne(text)
                } finally {
                    stream.close()
                }
            }
            if (update !is SegmentUpdate.Final) return
            result = update.text.trim()
            logText { "途中訳 $text → $result ${(System.nanoTime() - start) / 1_000_000}ms" }
        } finally {
            partialJobs.remove(requestId)
            handle(Event.PartialDone(requestId, result))
        }
    }

    /** 確定した発話を訳す。訳はトークンごとにカードへ出す。 */
    private suspend fun translate(turn: Turn, target: Language) {
        var done: Event.FinalDone? = null
        try {
            coroutineScope {
                val stream = Translations.open(this, app, TranslationStream.Options(turn.direction.source, target))
                try {
                    val result = stream.translateOne(turn.original) { text -> handle(Event.FinalProgress(turn.id, text)) }
                    done = when (result) {
                        is SegmentUpdate.Final -> Event.FinalDone(turn.id, result.text, result.millis)
                        is SegmentUpdate.Failed -> Event.FinalDone(turn.id, "", 0, failure = result.message)
                        is SegmentUpdate.Partial -> null
                    }
                } finally {
                    stream.close()
                }
            }
        } finally {
            // 終わらずに抜けた（画面を閉じた）ときも、制御が次の確定訳を渡せるようにする
            handle(done ?: Event.FinalDone(turn.id, "", 0, failure = "中断"))
        }
        session.turns.firstOrNull { it.id == turn.id }?.let {
            logText { "翻訳 [${it.direction.source.code}→${it.direction.target?.code}] ${it.original} → ${it.shownTranslation} ${it.statsText}" }
        }
    }

    override fun onCleared() {
        // 聞いていれば確定を待たずに聞き終える（音声の入力を止め、翻訳のスレッド数を戻す）
        handle(Event.Closed)
        // 認識中のジョブが終わってから解放される（呼んだスレッドは待たない）
        asr?.close()
        asr = null
    }

    private fun log(message: String) = Logs.i("会話: $message")

    /** 話した言葉や訳を含む行。詳しい記録を残すとき（デバッグ版の既定、正式版は詳細設定で選ぶ）だけ書く（[Logs.text]）。 */
    private fun logText(message: () -> String) = Logs.text { "会話: ${message()}" }

    /** 音量の目安。-55dBFS（静かな部屋）〜-15dBFS（近くで話す）を0〜1に割り当てる。 */
    private fun level(chunk: FloatArray): Float {
        if (chunk.isEmpty()) return 0f
        var sum = 0.0
        for (s in chunk) sum += s * s
        val db = 10 * kotlin.math.log10(sum / chunk.size + 1e-10)
        return ((db + 55) / 40).toFloat().coerceIn(0f, 1f)
    }

    companion object {
        /** 途中訳は話している途中の文なので短い。長く生成して確定訳を待たせないよう上限を低くする。 */
        private const val PARTIAL_MAX_TOKENS = 160

        /** 会話の区切り方。 */
        private val PROFILE = SegmentationProfile.CONVERSATION

        /** デバッグ版が会話の音声を書く、外部ファイルの中のフォルダー。 */
        const val RECORDING_DIR = "asr-rec"
    }
}
