package app.plamotranslate

import android.Manifest
import android.app.Application
import androidx.annotation.RequiresPermission
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.plamotranslate.asr.SpeechEngines
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.audio.MicRecorder
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import app.plamotranslate.translation.Translations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

data class TranslateUiState(
    val input: String = "",
    /** null は自動判定。既定は自動判定（2026-11-06、作者の要望）。 */
    val source: Language? = null,
    val target: Language = Language.JA,
    val output: String = "",
    val translating: Boolean = false,
    /** 直近の翻訳の所要時間など。 */
    val stats: String = "",
    val error: String? = null,
    val voice: VoiceState = VoiceState.Idle,
)

/** 原文の音声入力の状態。 */
enum class VoiceState { Idle, Loading, Listening }

/** テキスト翻訳画面の状態。モデルは [EngineHolder] がアプリ全体で1つ持つ。 */
class TranslateViewModel(private val app: Application) : AndroidViewModel(app) {
    private val _state = MutableStateFlow(TranslateUiState())
    val state: StateFlow<TranslateUiState> = _state.asStateFlow()
    val model: StateFlow<ModelState> = EngineHolder.state

    private var translateJob: Job? = null

    /** 音声入力の音声認識。初めてマイクを押したときに読み込む（会話翻訳と同じモデル）。 */
    private var asr: SpeechEngine? = null
    private var voiceJob: Job? = null

    /** 止めるボタンが押されたか。押されたら、話しかけの分も確定させてから止める。 */
    @Volatile private var stopVoice = false

    init {
        EngineHolder.ensureLoaded(app)
    }

    fun onInputChange(text: String) = _state.update { it.copy(input = text) }
    fun onSourceChange(lang: Language?) = _state.update { it.copy(source = lang) }
    fun onTargetChange(lang: Language) = _state.update { it.copy(target = lang) }

    /** 原文と訳文、訳す方向を入れ替える。自動判定のときは入れ替えない。 */
    fun swap() = _state.update {
        val src = it.source ?: return@update it
        it.copy(source = it.target, target = src, input = it.output.ifBlank { it.input }, output = "")
    }

    fun clear() {
        translateJob?.cancel()
        _state.update { it.copy(input = "", output = "", stats = "", error = null, translating = false) }
    }

    /**
     * 音声で原文を入れる（2026-11-08、作者の要望）。1回押すと1つの発話を聞き取り、話し終わったら止まって訳す。
     * 聞き取り中は途中の文字を原文の欄に出し、すでに書いてある原文の後ろに足す。
     * 聞き取れる言語は、設定の画面で選んだ音声認識モデル（`SpeechModel.languages`）に従う。
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startVoice() {
        if (voiceJob?.isActive == true) return
        stopVoice = false
        voiceJob = viewModelScope.launch {
            val engine = asr ?: loadAsr() ?: return@launch
            val base = _state.value.input
            _state.update { it.copy(voice = VoiceState.Listening, error = null) }
            val heard = AtomicReference<String?>(null)
            val events = Channel<SpeechEvent>(Channel.UNLIMITED)
            try {
                engine.listener = { events.trySend(it) }
                engine.languages = SpeechLanguages(separateChinese = _state.value.source?.isChinese == true)
                engine.reset()
                val spoke = AtomicBoolean(false)
                var silentChunks = 0
                val mic = launch(Dispatchers.IO) {
                    MicRecorder.stream().takeWhile { heard.get() == null && !stopVoice && (spoke.get() || silentChunks < WAIT_CHUNKS) }
                        .collect { chunk ->
                            silentChunks++
                            engine.accept(chunk)
                        }
                }
                // マイクが止まったら（確定した・止めるボタン・話し始めなかった）、話しかけだった分も確定させる
                mic.invokeOnCompletion { engine.flush() }
                for (event in events) {
                    when (event) {
                        is SpeechEvent.Speaking -> if (event.speaking) spoke.set(true)
                        is SpeechEvent.Partial -> if (heard.get() == null) _state.update { it.copy(input = joinSpoken(base, event.text)) }
                        // 最初の確定で止める
                        is SpeechEvent.Final -> if (heard.compareAndSet(null, event.utterance.text)) mic.cancel()
                        SpeechEvent.Flushed -> break
                        // 区切りと書き起こし直しは会話翻訳だけが使う
                        SpeechEvent.CutDone, is SpeechEvent.Retranscribed, is SpeechEvent.RetranscribeFailed, is SpeechEvent.Released -> Unit
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e.toString()) }
            } finally {
                engine.listener = {}
                _state.update { it.copy(voice = VoiceState.Idle) }
            }
            val text = heard.get()?.trim().orEmpty()
            _state.update { it.copy(input = joinSpoken(base, text)) }
            if (text.isNotEmpty()) translate()
        }
    }

    fun stopVoice() {
        stopVoice = true
    }

    private suspend fun loadAsr(): SpeechEngine? {
        val dir = ModelCatalog.asrDir(app)
        // 音声認識モデルは設定の画面で選んだもの（会話翻訳と同じ）。開くたびに読むので、切り替えは次に聞くときに効く
        val speechModel = RecognitionPreferences(app).model
        if (SpeechEngines.missingFiles(speechModel, dir).isNotEmpty()) {
            _state.update { it.copy(error = "音声認識のモデルがありません。トップ画面の「モデルの準備」でダウンロードしてください") }
            return null
        }
        _state.update { it.copy(voice = VoiceState.Loading, error = null) }
        val tuning = RecognitionPreferences(app)
        val result = withContext(Dispatchers.Default) {
            runCatching { SpeechEngines.load(speechModel, dir, tuning.profile(SegmentationProfile.CONVERSATION), tuning.asrTuning) }
        }
        _state.update { it.copy(voice = VoiceState.Idle) }
        return result.fold(
            { it.also { engine -> asr = engine } },
            { e -> _state.update { it.copy(error = "音声認識を読み込めませんでした：${e.message}") }; null },
        )
    }

    override fun onCleared() {
        voiceJob?.cancel()
        // 認識中のジョブが終わってから解放される（呼んだスレッドは待たない）
        asr?.close()
        asr = null
    }

    fun translate() {
        if (model.value !is ModelState.Ready) return
        val s = _state.value
        if (s.input.isBlank()) return
        // 前の翻訳が走っていれば止めて、最新の入力だけを訳す
        translateJob?.cancel()
        translateJob = viewModelScope.launch {
            _state.update { it.copy(output = "", translating = true, error = null, stats = "") }
            // 翻訳のストリームを1回ごとに開き、終わったら閉じる（docs/設計/翻訳コア.md「翻訳の契約」）
            val stream = Translations.open(this, app, TranslationStream.Options(s.source, s.target))
            try {
                when (val r = stream.translateOne(s.input) { text -> _state.update { it.copy(output = text) } }) {
                    is SegmentUpdate.Final -> _state.update {
                        it.copy(output = r.text, stats = "${r.tokens}トークン・${"%.2f".format(r.millis / 1000.0)}秒")
                    }
                    is SegmentUpdate.Failed -> _state.update { it.copy(error = r.message) }
                    is SegmentUpdate.Partial -> Unit
                }
            } finally {
                stream.close()
                _state.update { it.copy(translating = false) }
            }
        }
    }

    companion object {
        /** 話し始めずにこれだけ（0.1秒単位で8秒）たったら、聞き取りをやめる。 */
        private const val WAIT_CHUNKS = 80

        /**
         * 聞き取った文を、すでに書いてある原文の後ろに足す。日本語・中国語どうしは詰め、それ以外（韓国語・英語）は空白を挟む。
         */
        internal fun joinSpoken(base: String, spoken: String): String {
            val text = spoken.trim()
            if (text.isEmpty()) return base
            if (base.isBlank()) return text
            val last = base.last()
            val sep = if (last.isWhitespace() || (last.isCjk() && text.first().isCjk())) "" else " "
            return base + sep + text
        }

        private fun Char.isCjk() = this in '\u3000'..'\u9fff' || this in '\uff00'..'\uffef'
    }
}