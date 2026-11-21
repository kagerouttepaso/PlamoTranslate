package app.plamotranslate.desktop

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.ModelState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Path

internal data class SettingsState(
    val model: ModelState<Path> = ModelState.Loading,
    val defaultPath: Path,
    val selectedPath: Path = defaultPath,
    val busy: Boolean = true,
    val error: String? = null,
) {
    val modelStatus get() = model.toStatus()
}

internal data class ModelStatus(val label: String, val detail: String? = null, val isError: Boolean = false)

internal fun ModelState<Path>.toStatus(): ModelStatus = when (this) {
    ModelState.Loading -> ModelStatus("モデルを読み込み中…")
    is ModelState.Ready -> ModelStatus("モデル使用可 · ${if (gpu) "Vulkan" else "CPU"}", model.fileName.toString())
    is ModelState.Missing -> ModelStatus("モデルが見つかりません", path, isError = true)
    is ModelState.Failed -> ModelStatus("モデルの読み込みに失敗しました", message, isError = true)
}

internal class SettingsPresenter(
    private val scope: CoroutineScope,
    private val settings: DesktopSettings,
    model: StateFlow<ModelState<Path>>,
    private val loadModel: () -> Unit,
    private val selectModel: () -> Unit,
    private val reloadModel: () -> Unit,
    private val chooseModel: (Path) -> Path?,
) {
    private val mutableState = MutableStateFlow(SettingsState(model.value, settings.defaultModel))
    val state = mutableState.asStateFlow()

    init {
        scope.launch { model.collect { current -> mutableState.update { it.copy(model = current) } } }
        scope.launch {
            updateSelection("設定を読み込めませんでした。") { settings.read() }
            loadModel()
        }
    }

    fun choose() {
        if (state.value.busy) return
        val path = chooseModel(state.value.selectedPath) ?: return
        mutableState.update { it.copy(busy = true, error = null) }
        scope.launch {
            val saved = updateSelection("モデルの場所を保存できませんでした。") { settings.save(path) }
            if (saved) selectModel()
        }
    }

    fun reload() {
        if (!state.value.busy) reloadModel()
    }

    private suspend fun updateSelection(message: String, operation: () -> Unit): Boolean = try {
        withContext(Dispatchers.IO) { operation() }
        mutableState.update { it.copy(selectedPath = settings.model, busy = false, error = null) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logs.w(message, error)
        mutableState.update { it.copy(busy = false, error = message) }
        false
    }
}
