package app.plamotranslate.desktop

import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.models.ModelFile
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.pipeline.translation.ParallelTuner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.nio.file.Files
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
    val catalogStatus: Map<ModelFile, Long> = emptyMap(),
    val download: ModelPreparationProgress? = null,
    val initialSetup: Boolean? = null,
    val cpuOnly: Boolean = false,
    val gpuFallback: String? = null,
    val detailedLogs: Boolean = false,
) {
    val modelStatus get() = model.toStatus()
    fun availableBytes(file: ModelFile): Long = catalogStatus[file] ?: 0L

    fun hasRecognition(model: SpeechModel): Boolean = DesktopSpeechModels.requiredFiles(model).all { availableBytes(it) == it.size }
}

internal data class ModelPreparationProgress(
    val file: ModelFile? = null,
    val bytes: Long = 0L,
    val total: Long = 0L,
    val verifying: Boolean = false,
)

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
    private val audio: AudioPresenter,
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
            updateSettings("設定を読み込めませんでした。") {
                settings.read()
                Logs.allowUserText(settings.detailedLogs)
            }
            val needsSetup = withContext(Dispatchers.IO) { !Files.isRegularFile(settings.model) }
            mutableState.update { it.copy(initialSetup = needsSetup) }
            models.ensureLoaded()
        }
    }

    fun choose() {
        if (state.value.busy) return
        val path = chooseModel(state.value.selectedPath) ?: return
        mutableState.update { it.copy(busy = true, error = null, tuningStatus = null) }
        scope.launch {
            val saved = updateSettings("モデルの場所を保存できませんでした。") { settings.save(path) }
            if (saved) {
                models.select()
                mutableState.update { it.copy(initialSetup = false) }
            }
        }
    }

    fun reload() {
        if (!state.value.busy) models.reload()
    }

    fun prepareModels(translation: DesktopModelCatalog? = null, recognition: SpeechModel? = null) {
        if (state.value.busy || recognition != null && !audio.state.value.canConfigure) return
        mutableState.update { it.copy(busy = true, error = null, download = ModelPreparationProgress()) }
        downloadJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                withContext(Dispatchers.IO) {
                    // 初回は音声認識から準備し、途中でアプリを閉じても次回の起動で続きから取る。
                    val speech = recognition?.let(DesktopSpeechModels::downloadFiles).orEmpty().filterNot(::hasSpeechFile)
                    downloadFiles(speech + listOfNotNull(translation?.file))
                }
                if (translation != null) {
                    if (!updateSettings("モデルの選択を保存できませんでした。") { settings.save(translation.path(settings.modelDirectory)) }) {
                        return@launch
                    }
                    models.select()
                }
                recognition?.let(audio::usePreparedRecognition)
                if (translation != null) mutableState.update { it.copy(initialSetup = false) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                currentCoroutineContext().ensureActive()
                Logs.w("モデルを準備できませんでした。", error)
                mutableState.update { it.copy(error = error.message ?: "モデルを準備できませんでした。") }
            } finally {
                val status = withContext(NonCancellable + Dispatchers.IO) { catalogStatus() }
                mutableState.update { it.copy(download = null, busy = false, catalogStatus = status) }
            }
        }
    }

    private suspend fun downloadFiles(files: List<ModelFile>) {
        val total = files.sumOf { it.size }
        var completed = 0L
        for (file in files) {
            downloads.prepare(file) { progress ->
                val current = ModelPreparationProgress(file, completed + progress.bytes, total, progress.verifying)
                mutableState.update { it.copy(download = current) }
            }
            completed += file.size
        }
    }

    private fun hasSpeechFile(file: ModelFile) = DesktopSpeechModels.isInstalled(settings.modelDirectory, file)

    private fun catalogStatus(): Map<ModelFile, Long> = runCatching {
        val translationFiles = DesktopModelCatalog.entries.map { it.file }
        translationFiles.associateWith(downloads::bytesAvailable) + DesktopSpeechModels.files.associateWith { file ->
            if (hasSpeechFile(file)) file.size else downloads.bytesAvailable(file).coerceAtMost(file.size - 1)
        }
    }.getOrDefault(emptyMap())

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun setCpuOnly(enabled: Boolean) {
        if (state.value.cpuOnly == enabled) return
        changeBackend { settings.saveCpuOnly(enabled) }
    }

    fun retryGpu() = changeBackend(settings::retryGpu)

    fun setDetailedLogs(enabled: Boolean) {
        if (state.value.busy || state.value.detailedLogs == enabled) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            updateSettings("ログの設定を保存できませんでした。") {
                settings.saveDetailedLogs(enabled)
                Logs.allowUserText(enabled)
            }
        }
    }

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
            detailedLogs = settings.detailedLogs,
        )
    }

    private suspend fun updateSettings(message: String, operation: () -> Unit): Boolean = try {
        val catalogStatus = withContext(Dispatchers.IO) {
            operation()
            catalogStatus()
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
