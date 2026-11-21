package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import kotlinx.coroutines.CancellationException
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
    val error: String? = null,
) {
    val canTranslate get() = model is ModelState.Ready && input.isNotBlank() && !translating
    val canCopy get() = output.isNotBlank() && !translating
    val modelStatus get() = model.toStatus()
    val sourceLabel get() = source?.label ?: "自動"
}

internal class TextPresenter(
    private val scope: CoroutineScope,
    model: StateFlow<ModelState<Path>>,
    private val translations: DesktopTranslations,
    private val copy: (String) -> Unit,
) {
    private val mutableState = MutableStateFlow(TranslationState(model = model.value))
    val state = mutableState.asStateFlow()
    private var translation: Job? = null

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

    fun selectTarget(language: Language) = reset { it.copy(target = language) }

    fun translate() {
        val request = state.value
        if (!request.canTranslate) return
        mutableState.update { it.copy(translating = true, output = "", error = null) }
        translation = scope.launch {
            val stream = translations.open(this, TranslationStream.Options(request.source, request.target))
            try {
                when (val result = stream.translateOne(request.input) { partial ->
                    mutableState.update { it.copy(output = partial) }
                }) {
                    is SegmentUpdate.Final -> {
                        mutableState.update { it.copy(output = result.text, translating = false) }
                        Logs.i("翻訳完了: ${result.millis} ms（キャッシュ=${result.cached}）")
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
        runCatching { copy(current.output) }.onFailure {
            mutableState.update { it.copy(error = "訳文をコピーできませんでした。") }
        }
    }

    private fun reset(change: (TranslationState) -> TranslationState) {
        translation?.cancel()
        translation = null
        mutableState.update { change(it).copy(output = "", translating = false, error = null) }
    }
}
