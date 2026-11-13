package app.plamotranslate.conversation

import android.Manifest
import android.app.Application
import android.content.pm.ApplicationInfo
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.ReadAloud
import app.plamotranslate.asr.SenseVoiceAsr
import app.plamotranslate.asr.WavFile
import app.plamotranslate.audio.MicRecorder
import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.pipeline.conversation.ConversationSession
import app.plamotranslate.pipeline.conversation.ConversationSession.Effect
import app.plamotranslate.pipeline.conversation.ConversationSession.Event
import app.plamotranslate.pipeline.conversation.Direction
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.conversation.Turn
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import app.plamotranslate.translation.Translations
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
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
    /** モデルのファイルが無い。adbで置いてもらう（docs/手順/開発環境.md）。 */
    data class Missing(val paths: List<String>) : AsrState
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
    /** 今、自分の発話を訳す先の言語。 */
    val partner: Language = Language.KO,
    val turns: List<Turn> = emptyList(),
    /** マイクの音量（0〜1）。聞いている間だけ動く。 */
    val level: Float = 0f,
    /** 話している最中なら、ここまでの聞き取り（まだ文字になっていなければ空）。 */
    val live: LiveSpeech? = null,
    val error: String? = null,
)

/**
 * 会話翻訳の外とつなぐ部分（docs/設計/会話翻訳.md「全体の流れ」）。マイクの音声を音声認識のスレッドで [SpeechEngine] に流し、
 * 届いた出来事を中核の [ConversationSession] に渡して、返ってきた作業（確定訳・途中訳・途中訳の取り消し）を翻訳の流れで行う。
 * 何をいつ訳すか、方向、カードと聞き取り中の表示は [ConversationSession] が決める。翻訳モデルは [EngineHolder] のものを使う。
 * [ConversationSession] は画面のスレッドだけから呼ぶ。
 */
class ConversationViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(ConversationUiState())
    val state: StateFlow<ConversationUiState> = _state.asStateFlow()
    val model: StateFlow<ModelState> = EngineHolder.state

    /** 表示モードと自分の言語は次に開いたときも同じにする。init より前に置く（init で読む）。 */
    private val preferences = ConversationPreferences(app)

    private val session = ConversationSession(self = preferences.self ?: Language.JA)

    private var asr: SpeechEngine? = null
    private var listenJob: Job? = null

    /** 走っている途中訳。確定訳が来たら取り消す。 */
    private val partialJobs = mutableMapOf<Long, Job>()

    init {
        preferences.viewMode?.let { mode -> _state.update { it.copy(viewMode = mode) } }
        sync()
        EngineHolder.ensureLoaded(app)
        loadAsr()
    }

    fun loadAsr() {
        val dir = ModelCatalog.asrDir(app)
        val missing = SenseVoiceAsr.missingFiles(dir)
        if (missing.isNotEmpty()) {
            _state.update { it.copy(asr = AsrState.Missing(missing)) }
            return
        }
        _state.update { it.copy(asr = AsrState.Loading) }
        viewModelScope.launch {
            val result = withContext(asrDispatcher) { runCatching { SenseVoiceAsr.load(dir, PROFILE) } }
            result.onSuccess { asr = it.also { engine -> engine.languages = session.speechLanguages } }
            _state.update {
                it.copy(asr = result.fold({ AsrState.Ready }, { e -> AsrState.Failed(e.message ?: e.toString()) }))
            }
        }
    }

    fun setMode(mode: PartnerMode) = handle(Event.ModeChanged(mode))

    /** 自分の言語を変える。相手が同じ言語なら、相手は自動に戻る。 */
    fun setSelf(self: Language) {
        preferences.self = self
        handle(Event.SelfChanged(self))
    }

    fun setViewMode(mode: ViewMode) {
        preferences.viewMode = mode
        _state.update { it.copy(viewMode = mode) }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun start() = listen { engine ->
        val recording = recorder()
        try {
            MicRecorder.stream().collect { chunk ->
                val fed = feed(engine, chunk)
                recording?.write(fed)
            }
        } finally {
            recording?.close()
        }
    }

    /**
     * デバッグ版だけ、音声認識へ渡した音声を WAV に書く（docs/設計/会話翻訳.md「動作確認」）。[startFromWav] で流し直して、
     * 区切りと確定の文を前後で比べるため。会話の中身なので、端末の外部ファイルの `asr-rec/` にだけ置き、区切りで消す。
     */
    private fun recorder(): WavFile.Writer? {
        if (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
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
            feed(engine, padded.copyOfRange(from, minOf(from + chunk, padded.size)))
            val spentMs = (System.nanoTime() - begin) / 1_000_000
            Thread.sleep((100 - spentMs).coerceAtLeast(0))
        }
        log("音声ファイルの認識が終わりました: ${File(path).name}")
    }

    /** 音声を音声認識に渡し、渡した音声を返す（読み上げの間は無音に置き換えたもの）。音声認識のスレッドで呼ぶ。 */
    private fun feed(engine: SpeechEngine, raw: FloatArray): FloatArray {
        // 訳文を読み上げている間とその直後は、マイクの音を無音に置き換える。読み上げの声を発話として拾わないため（2026-11-08）
        val chunk = if (ReadAloud.isAudible()) FloatArray(raw.size) else raw
        _state.update { it.copy(level = level(chunk)) }
        post(engine.accept(chunk))
        return chunk
    }

    fun stop() {
        listenJob?.cancel()
    }

    /** 今話している分を確定して次のカードにする（聞くのは続ける）。音声認識は同じスレッドから呼ぶ。 */
    fun cut() {
        val engine = asr ?: return
        if (listenJob?.isActive != true) return
        viewModelScope.launch(asrDispatcher) { post(engine.cut()) }
    }

    fun clear() {
        handle(Event.Cleared)
        _state.update { it.copy(error = null) }
    }

    private fun listen(block: suspend (SpeechEngine) -> Unit) {
        val engine = asr ?: return
        if (listenJob?.isActive == true) return
        listenJob = viewModelScope.launch {
            _state.update { it.copy(listening = true, error = null) }
            try {
                withContext(asrDispatcher) {
                    engine.reset()
                    try {
                        block(engine)
                    } finally {
                        // 止めたときに話しかけだった分も訳す
                        post(engine.flush())
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                _state.update { it.copy(listening = false, level = 0f) }
                handle(Event.Stopped)
            }
        }
    }

    /** 音声認識のスレッドから、出来事を画面のスレッドの制御へ順に渡す（画面のスレッドは届いた順に処理する）。 */
    private fun post(events: List<SpeechEvent>) {
        if (events.isEmpty()) return
        viewModelScope.launch { events.forEach { onSpeech(it) } }
    }

    private fun onSpeech(event: SpeechEvent) {
        when (event) {
            is SpeechEvent.Partial -> logText { "途中 [${event.lang}] ${event.text}" }
            is SpeechEvent.Final -> event.utterance.let { u -> logText { "認識 [${u.lang}] ${u.text} 認識${u.recognizeMs}ms" } }
            is SpeechEvent.Speaking -> Unit
        }
        handle(Event.Speech(event))
    }

    /** 制御に出来事を渡し、返ってきた作業を行い、画面の状態をそろえる。画面のスレッドから呼ぶ。 */
    private fun handle(event: Event) {
        val effects = session.handle(event)
        sync()
        for (effect in effects) when (effect) {
            is Effect.TranslateFinal -> viewModelScope.launch { translate(effect.turn) }
            is Effect.TranslatePartial -> {
                // 登録してから始める（画面のスレッドで始めると、その場で終わって後始末が登録より先に走ることがある）
                val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
                    translatePartial(effect.requestId, effect.text, effect.direction)
                }
                partialJobs[effect.requestId] = job
                job.start()
            }
            is Effect.CancelPartial -> partialJobs[effect.requestId]?.cancel()
        }
    }

    private fun sync() {
        // 音声認識が話し手の交代を見る言語は、会話の言語（自分か相手が中国語のときだけ、中国語と日本語を分ける）
        asr?.languages = session.speechLanguages
        _state.update {
            it.copy(turns = session.turns, live = session.live, self = session.self, mode = session.mode, partner = session.partner)
        }
    }

    /** 途中の文字を訳す。終わったら（取り消されても）制御に知らせる。 */
    private suspend fun translatePartial(requestId: Long, text: String, direction: Direction) {
        var result: String? = null
        try {
            if (model.value !is ModelState.Ready) return
            val start = System.nanoTime()
            // 翻訳の流れを1回ごとに開き、終わったら閉じる（docs/設計/翻訳コア.md「翻訳の契約」）。
            // 確定訳が来たら制御がこのコルーチンを取り消すので、流れも閉じて途中訳の生成を止める
            val update = coroutineScope {
                val stream = Translations.open(
                    this, app, TranslationStream.Options(direction.source, direction.target, maxTokens = PARTIAL_MAX_TOKENS),
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
    private suspend fun translate(turn: Turn) {
        var done: Event.FinalDone? = null
        try {
            coroutineScope {
                val stream = Translations.open(this, app, TranslationStream.Options(turn.direction.source, turn.direction.target))
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
            logText { "翻訳 [${it.direction.source.code}→${it.direction.target.code}] ${it.original} → ${it.shownTranslation} ${it.statsText}" }
        }
    }

    override fun onCleared() {
        listenJob?.cancel()
        val engine = asr
        asr = null
        // 認識中のスレッドが使い終わってから解放する
        asrExecutor.execute { engine?.close() }
    }

    private fun log(message: String) = Logs.i("会話: $message")

    /** 話した言葉や訳を含む行。正式版では書かない（[Logs.text]）。 */
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

        /** 音声認識は同じスレッドから順に呼ぶ。 */
        private val asrExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "PlamoAsr") }
        private val asrDispatcher = asrExecutor.asCoroutineDispatcher()
    }
}
