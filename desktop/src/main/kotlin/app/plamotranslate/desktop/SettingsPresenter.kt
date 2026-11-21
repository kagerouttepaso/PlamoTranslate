package app.plamotranslate.desktop

import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.pipeline.translation.ParallelTuner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
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
    val threads: Int = 0,
    val automaticThreads: Int? = null,
    val manualParallel: Int? = null,
    val automaticParallel: Int = TranslationEngine.DEFAULT_PARALLEL,
    val tuningStatus: String? = null,
    val catalogStatus: Map<DesktopModelCatalog, Long> = emptyMap(),
    val download: ModelDownloadProgress? = null,
    val cpuOnly: Boolean = false,
    val gpuFallback: String? = null,
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
    private val models: ModelHost<Path, TranslationEngine>,
    private val chooseModel: (Path) -> Path?,
) {
    private val benchmark = DesktopTranslationBenchmark(models)
    private val downloads = DesktopModelDownloads(settings.modelDirectory)
    private var downloadJob: Job? = null
    private val mutableState = MutableStateFlow(SettingsState(models.state.value, settings.defaultModel))
    val state = mutableState.asStateFlow()

    init {
        scope.launch { models.state.collect { current -> mutableState.update { withModel(it, current) } } }
        scope.launch {
            updateSettings("設定を読み込めませんでした。") { settings.read() }
            models.ensureLoaded()
        }
    }

    fun choose() {
        if (state.value.busy) return
        val path = chooseModel(state.value.selectedPath) ?: return
        mutableState.update { it.copy(busy = true, error = null, tuningStatus = null) }
        scope.launch {
            val saved = updateSettings("モデルの場所を保存できませんでした。") { settings.save(path) }
            if (saved) models.select()
        }
    }

    fun reload() {
        if (!state.value.busy) models.reload()
    }

    fun selectModel(model: DesktopModelCatalog) {
        if (state.value.busy || state.value.model is ModelState.Loading) return
        val path = model.path(settings.modelDirectory)
        mutableState.update {
            it.copy(busy = true, error = null, download = ModelDownloadProgress(model, it.catalogStatus[model] ?: 0))
        }
        downloadJob = scope.launch {
            try {
                withContext(Dispatchers.IO) { downloads.prepare(model) { progress -> mutableState.update { it.copy(download = progress) } } }
                if (updateSettings("モデルの選択を保存できませんでした。") { settings.save(path) }) {
                    models.select()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                Logs.w("モデルを準備できませんでした。", error)
                mutableState.update { it.copy(error = error.message ?: "モデルを準備できませんでした。") }
            } finally {
                val status = withContext(NonCancellable + Dispatchers.IO) { runCatching { downloads.status() }.getOrDefault(emptyMap()) }
                mutableState.update { it.copy(download = null, busy = false, catalogStatus = status) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun setCpuOnly(enabled: Boolean) {
        if (state.value.cpuOnly == enabled) return
        changeBackend { settings.saveCpuOnly(enabled) }
    }

    fun retryGpu() = changeBackend(settings::retryGpu)

    private fun changeBackend(save: () -> Unit) {
        if (state.value.busy || state.value.model is ModelState.Loading) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            if (updateSettings("実行先を保存できませんでした。", save)) models.reload()
        }
    }

    fun setThreads(count: Int) {
        val current = state.value
        if (current.busy || current.model is ModelState.Loading || current.threads == count) return
        mutableState.update { it.copy(busy = true, tuningStatus = null) }
        scope.launch {
            val saved = updateSettings("スレッド数を保存できませんでした。") { settings.saveThreads(count) }
            if (saved && current.model is ModelState.Ready) models.reload()
        }
    }

    fun setParallel(count: Int?) {
        val current = state.value
        val ready = current.model as? ModelState.Ready ?: return
        if (current.busy || current.manualParallel == count) return
        mutableState.update { it.copy(busy = true, tuningStatus = null) }
        scope.launch {
            val saved = updateSettings("同時に訳す数を保存できませんでした。") {
                settings.saveManualParallel(ready.model, ready.gpu, count)
            }
            if (saved) models.setParallel(count ?: settings.automaticParallel(ready.model, ready.gpu))
        }
    }

    fun tuneParallel() {
        val current = state.value
        val ready = current.model as? ModelState.Ready ?: return
        if (current.busy) return
        mutableState.update { it.copy(busy = true, error = null, tuningStatus = "調整しています…") }
        scope.launch {
            try {
                val measured = linkedMapOf<Int, Double>()
                while (true) {
                    val count = ParallelTuner.next(measured) ?: break
                    mutableState.update { it.copy(tuningStatus = "同時に $count 件で測っています…") }
                    measured[count] = measure(ready.generation, count)
                }
                if (measured.values.none { it > 0 }) {
                    mutableState.update { it.copy(error = "同時に訳す数を調整できませんでした。") }
                    return@launch
                }
                val count = ParallelTuner.best(measured, TranslationEngine.DEFAULT_PARALLEL)
                val saved = updateSettings("調整結果を保存できませんでした。") {
                    settings.saveTunedParallel(ready.model, ready.gpu, count)
                }
                if (saved) models.setParallel(count)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val message = "同時に訳す数を調整できませんでした。"
                Logs.w(message, error)
                mutableState.update { it.copy(error = message) }
            } finally {
                mutableState.update { it.copy(busy = false, tuningStatus = null) }
            }
        }
    }

    private suspend fun measure(generation: Long, count: Int): Double = try {
        benchmark.measure(generation, count) ?: 0.0
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logs.w("同時に $count 件訳す計測に失敗しました。", error)
        0.0
    }

    private fun withModel(state: SettingsState, model: ModelState<Path>): SettingsState {
        val ready = model as? ModelState.Ready
        return state.copy(
            model = model,
            automaticThreads = if (ready == null) state.automaticThreads else TranslationEngine.defaultThreads(),
            manualParallel = ready?.let { settings.manualParallel(it.model, it.gpu) },
            automaticParallel = ready?.let { settings.automaticParallel(it.model, it.gpu) } ?: TranslationEngine.DEFAULT_PARALLEL,
            cpuOnly = settings.cpuOnly,
            gpuFallback = settings.gpuFallback,
        )
    }

    private suspend fun updateSettings(message: String, operation: () -> Unit): Boolean = try {
        val catalogStatus = withContext(Dispatchers.IO) {
            operation()
            runCatching { downloads.status() }.getOrDefault(emptyMap())
        }
        mutableState.update {
            withModel(it, models.state.value).copy(selectedPath = settings.model, threads = settings.threads,
                catalogStatus = catalogStatus, busy = it.download != null, error = null)
        }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        Logs.w(message, error)
        mutableState.update { it.copy(busy = false, error = message) }
        false
    }
}
