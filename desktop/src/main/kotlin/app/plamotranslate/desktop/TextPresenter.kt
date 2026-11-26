package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.nio.file.Path

internal data class TranslationState(
    val model: ModelState<Path> = ModelState.Loading,
    val input: String = "",
    val source: Language? = null,
    val target: Language = Language.JA,
    val output: String = "",
    val translating: Boolean = false,
    val voice: TextVoicePhase = TextVoicePhase.IDLE,
    val error: String? = null,
    /** 訳の下に小さく出す、生成したトークン数とかかった時間（Android 版のテキスト翻訳と同じ）。 */
    val stats: String = "",
) {
    val canTranslate get() = model is ModelState.Ready && input.isNotBlank() && !translating && voice == TextVoicePhase.IDLE
    val canCopy get() = output.isNotBlank() && !translating
    val canSwap get() = source != null && !translating && voice == TextVoicePhase.IDLE
    val modelStatus get() = model.toStatus()
    val sourceLabel get() = source?.label ?: "自動"
}

internal enum class TextVoicePhase { IDLE, LOADING, LISTENING, FINISHING }

internal class TextPresenter(
    private val scope: CoroutineScope,
    model: StateFlow<ModelState<Path>>,
    private val openTranslation: (CoroutineScope, TranslationStream.Options) -> TranslationStream,
    private val paste: () -> String?,
    private val copy: (String) -> Unit,
    private val voiceInput: DesktopTextVoice,
    private val recognition: () -> DesktopRecognitionSettings,
    private val saveTarget: (Language) -> Unit,
    private val qwenInUse: () -> Boolean = { false },
) {
    private val mutableState = MutableStateFlow(TranslationState(model = model.value))
    val state = mutableState.asStateFlow()
    private var translation: Job? = null
    private var voice: Job? = null
    private var finishVoice: CompletableDeferred<Unit>? = null

    init {
        scope.launch {
            model.collect { current ->
                if (current != state.value.model) {
                    reset { it.copy(model = current) }
                }
            }
        }
    }

    fun edit(text: String) = reset { it.copy(input = text) }

    fun selectSource(language: Language?) = reset { it.copy(source = language) }

    fun selectTarget(language: Language) {
        if (state.value.target != language) reset { it.copy(target = language) }
    }

    fun swap() {
        val current = state.value
        val source = current.source ?: return
        if (!current.canSwap) return
        reset { it.copy(source = current.target, target = source, input = current.output.ifBlank { current.input }) }
        saveTarget(source)
    }

    fun clear() = edit("")

    fun pasteAndTranslate() {
        val text = try {
            paste()
        } catch (error: Exception) {
            Logs.w("クリップボードを読めませんでした", error)
            null
        }
        if (text.isNullOrBlank()) {
            mutableState.update { it.copy(error = "クリップボードに貼り付けられる文字がありません。") }
            return
        }
        edit(text)
        translate()
    }

    fun translate() {
        val request = state.value
        if (!request.canTranslate) return
        mutableState.update { it.copy(translating = true, output = "", error = null, stats = "") }
        translation = scope.launch {
            val stream = openTranslation(this, TranslationStream.Options(request.source, request.target))
            try {
                when (val result = stream.translateOne(request.input) { partial ->
                    mutableState.update { it.copy(output = partial) }
                }) {
                    is SegmentUpdate.Final -> {
                        val stats = if (result.cached) "保存した訳" else "${result.tokens}トークン・${"%.2f".format(result.millis / 1000.0)}秒"
                        mutableState.update { it.copy(output = result.text, translating = false, stats = stats) }
                        Logs.i("翻訳完了: ${result.millis} ms（キャッシュ=${result.cached}）")
                        Logs.text { "テキスト翻訳: ${request.input.take(4000)} → ${result.text.take(4000)}" }
                    }
                    is SegmentUpdate.Failed -> mutableState.update {
                        it.copy(output = "", translating = false, error = result.message)
                    }
                    is SegmentUpdate.Partial -> Unit
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logs.e("翻訳を完了できませんでした", error)
                mutableState.update { it.copy(output = "", translating = false, error = "翻訳を完了できませんでした。") }
            } finally {
                stream.close()
            }
        }
    }

    fun cancel() = reset { it }

    fun copyOutput() {
        val current = state.value
        if (!current.canCopy) return
        copyText(current.output, "訳文")
    }

    fun copyInput() = copyText(state.value.input, "原文")

    private fun copyText(text: String, label: String) {
        if (text.isBlank()) return
        runCatching { copy(text) }.onFailure {
            mutableState.update { it.copy(error = "${label}をコピーできませんでした。") }
        }
    }

    fun startVoice() {
        val current = state.value
        if (voice?.isActive == true || current.translating) return
        val settings = recognition()
        if (settings.model == SpeechModel.QWEN3_ASR && qwenInUse()) {
            mutableState.update { it.copy(error = "音声翻訳を止めてから、音声入力を使ってください。") }
            return
        }
        val finished = CompletableDeferred<Unit>()
        finishVoice = finished
        mutableState.update { it.copy(voice = TextVoicePhase.LOADING, output = "", error = null) }
        voice = scope.launch {
            var final = false
            try {
                voiceInput.listen(settings, current.source, finished).collect { update ->
                    when (update) {
                        TextVoiceUpdate.Listening -> mutableState.update { it.copy(voice = TextVoicePhase.LISTENING) }
                        is TextVoiceUpdate.Partial -> mutableState.update { it.copy(input = appendSpeech(current.input, update.text)) }
                        is TextVoiceUpdate.Final -> {
                            final = true
                            mutableState.update { it.copy(input = appendSpeech(current.input, update.text)) }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: LinkageError) {
                Logs.w("Windows の音声ライブラリを読み込めませんでした", error)
                mutableState.update { it.copy(error = "音声ライブラリを読み込めません。DLL の配置を確認してください。") }
            } catch (error: Exception) {
                Logs.w("音声を入力できませんでした", error)
                mutableState.update { it.copy(error = error.message ?: "音声を入力できませんでした。") }
            } finally {
                if (finishVoice === finished) {
                    finishVoice = null
                    mutableState.update { it.copy(voice = TextVoicePhase.IDLE, input = if (final) it.input else current.input) }
                }
            }
            if (final) translate()
        }
    }

    fun stopVoice() {
        if (state.value.voice != TextVoicePhase.LISTENING) return
        mutableState.update { it.copy(voice = TextVoicePhase.FINISHING) }
        finishVoice?.complete(Unit)
    }

    private fun appendSpeech(base: String, spoken: String): String = when {
        spoken.isBlank() -> base
        base.isBlank() -> spoken
        else -> "${base.trimEnd()} $spoken"
    }

    private fun cancelVoice() {
        finishVoice = null
        voice?.cancel()
        voice = null
    }

    fun leave() {
        when (state.value.voice) {
            TextVoicePhase.LISTENING -> stopVoice()
            TextVoicePhase.LOADING -> {
                cancelVoice()
                mutableState.update { it.copy(voice = TextVoicePhase.IDLE) }
            }
            else -> Unit
        }
    }

    private fun reset(change: (TranslationState) -> TranslationState) {
        translation?.cancel()
        translation = null
        cancelVoice()
        mutableState.update { change(it).copy(output = "", translating = false, voice = TextVoicePhase.IDLE, error = null, stats = "") }
    }
}
