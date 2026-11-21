package app.plamotranslate.desktop

import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.ParallelTranslationBenchmark
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.diagnostics.GpuStartupPolicy
import app.plamotranslate.pipeline.translation.EngineLoader
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.PersistentCache
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.translateOne
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import kotlin.io.path.isRegularFile

internal class DesktopSettings(private val directory: Path) {
    private val file = directory.resolve("settings.properties")
    val modelDirectory: Path = directory.resolve("models")
    val defaultModel: Path = DesktopModelCatalog.SMALL.path(modelDirectory)
    private val selectedTranslationTarget = MutableStateFlow(Language.JA)
    val translationTarget = selectedTranslationTarget.asStateFlow()

    fun selectTranslationTarget(target: Language) {
        selectedTranslationTarget.value = target
    }

    @Volatile private var properties = Properties()
    val model: Path get() = properties.getProperty("model")?.let { Path.of(it) } ?: defaultModel
    val threads: Int get() = properties.getProperty("threads")?.toIntOrNull()?.coerceAtLeast(0) ?: 0
    val cpuOnly: Boolean get() = properties.getProperty("cpuOnly").toBoolean()
    val gpuFallback: String? get() = properties.getProperty("gpuFallback")

    @Synchronized
    fun read() {
        if (!Files.exists(file)) return
        val read = Properties()
        Files.newInputStream(file).use(read::load)
        val savedPath = read.getProperty("model")
        if (savedPath.isNullOrBlank()) read.remove("model") else Path.of(savedPath)
        properties = read
    }

    fun save(path: Path) = update { setProperty("model", path.toString()) }

    fun saveThreads(count: Int) = update { setProperty("threads", count.toString()) }

    fun saveCpuOnly(enabled: Boolean) = update { setProperty("cpuOnly", enabled.toString()) }

    fun retryGpu() = update {
        remove("cpuOnly")
        remove("gpuFallback")
        remove("gpuLoadingPid")
        remove("gpuInterruptions")
    }

    @Synchronized
    fun beginGpuLoad(): Boolean {
        val pid = ProcessHandle.current().pid()
        val previous = properties.getProperty("gpuLoadingPid")?.toLongOrNull()
        if (previous != null && previous != pid && ProcessHandle.of(previous).map { it.isAlive }.orElse(false)) return false
        val interruptions = properties.getProperty("gpuInterruptions")?.toIntOrNull() ?: 0
        val decision = GpuStartupPolicy.decide(gpuFallback != null, previous != null, interruptions,
            abnormal = false, intended = false, exitLabel = "終了理由の記録なし")
        if (decision == GpuStartupPolicy.Decision.KeepCpu) return false
        update {
            when (decision) {
                is GpuStartupPolicy.Decision.Disable -> {
                    setProperty("gpuFallback", decision.reason)
                    remove("gpuLoadingPid")
                    remove("gpuInterruptions")
                }
                else -> {
                    setProperty("gpuLoadingPid", pid.toString())
                    if (decision is GpuStartupPolicy.Decision.Retry) setProperty("gpuInterruptions", decision.interruptions.toString())
                }
            }
        }
        return decision !is GpuStartupPolicy.Decision.Disable
    }

    fun endGpuLoad(failure: String? = null) = update {
        remove("gpuLoadingPid")
        remove("gpuInterruptions")
        if (failure != null) setProperty("gpuFallback", failure)
    }

    fun manualParallel(model: Path, gpu: Boolean): Int? = parallelValue(parallelKey("manual", model, gpu))

    fun automaticParallel(model: Path, gpu: Boolean, default: Int = TranslationEngine.DEFAULT_PARALLEL): Int =
        parallelValue(parallelKey("tuned", model, gpu)) ?: default

    fun saveManualParallel(model: Path, gpu: Boolean, count: Int?) = update {
        val key = parallelKey("manual", model, gpu)
        if (count == null) remove(key) else setProperty(key, count.toString())
    }

    fun saveTunedParallel(model: Path, gpu: Boolean, count: Int) = update {
        setProperty(parallelKey("tuned", model, gpu), count.toString())
        remove(parallelKey("manual", model, gpu))
    }

    private fun parallelValue(key: String): Int? = properties.getProperty(key)?.toIntOrNull()
        ?.takeIf { it in 1..TranslationEngine.MAX_PARALLEL }

    private fun parallelKey(mode: String, model: Path, gpu: Boolean): String {
        val backend = if (gpu) "vulkan" else "cpu"
        val path = model.toAbsolutePath().normalize().toString().lowercase(Locale.ROOT)
        return "parallel.$mode.$backend.$path"
    }

    @Synchronized
    private fun update(change: Properties.() -> Unit) {
        val updated = Properties().apply {
            putAll(properties)
            change()
        }
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "settings-", ".tmp")
        try {
            Files.newOutputStream(temp).use { updated.store(it, null) }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            properties = updated
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

internal class DesktopEngineLoader(
    private val nativeDir: String,
    private val preferGpu: Boolean,
    private val settings: DesktopSettings,
) : EngineLoader<Path, TranslationEngine> {
    override fun missing(model: Path): String? = model.toString().takeUnless { model.isRegularFile() }

    override suspend fun load(model: Path, parallel: Int): TranslationEngine = withContext(Dispatchers.IO) {
        DesktopModelCatalog.entries.firstOrNull { it.path(settings.modelDirectory) == model }?.let { entry ->
            check(DesktopModelDownloads.verify(model, entry)) { "モデルを照合できません。設定からモデルを選び直してください。" }
        }
        val gpu = preferGpu && !settings.cpuOnly && settings.beginGpuLoad()
        try {
            val engine = loadEngine(model, gpu, parallel)
            if (gpu && engine.usesGpu) {
                try {
                    warmUp(engine)
                } catch (error: Exception) {
                    engine.close()
                    if (error is kotlinx.coroutines.CancellationException) throw error
                    Logs.w("GPUでの準備用の推論に失敗しました。CPUで動かします。", error)
                    settings.endGpuLoad("GPUでの準備用の推論に失敗しました")
                    return@withContext loadEngine(model, false, parallel)
                }
            }
            if (gpu) settings.endGpuLoad()
            engine
        } catch (error: LinkageError) {
            if (gpu) settings.endGpuLoad()
            Logs.e("Windows の翻訳ライブラリを読み込めませんでした", error)
            throw IllegalStateException("翻訳ライブラリを読み込めません。DLL の配置を確認して再起動してください。", error)
        } catch (error: Exception) {
            if (gpu) settings.endGpuLoad()
            throw error
        }
    }

    private suspend fun loadEngine(model: Path, gpu: Boolean, parallel: Int): TranslationEngine =
        TranslationEngine.load(model.toFile(), nativeDir, gpu, cpu = CpuOptions(threads = settings.threads)).also {
            it.parallel = parallel
        }

    private suspend fun warmUp(engine: TranslationEngine) = coroutineScope {
        val stream = TranslationStream(this, { TranslationStream.Backend(engine, ModelOrigin("warmup", 0)) },
            TranslationStream.Options(Language.KO, Language.JA, maxTokens = 8))
        try {
            val result = stream.translateOne("안녕하세요")
            if (result is SegmentUpdate.Failed) error(result.message)
        } finally {
            stream.close()
        }
    }

    override fun usesGpu(engine: TranslationEngine): Boolean = engine.usesGpu

    override fun preferredParallel(model: Path, gpu: Boolean, default: Int): Int =
        settings.manualParallel(model, gpu) ?: settings.automaticParallel(model, gpu, default)

    override fun setParallel(engine: TranslationEngine, parallel: Int) {
        engine.parallel = parallel
    }

    override suspend fun close(engine: TranslationEngine) = engine.close()
}

internal class DesktopTranslationBenchmark(private val models: ModelHost<Path, TranslationEngine>) {
    suspend fun measure(generation: Long, parallel: Int): Double? {
        val (engine, ready) = models.awaitEngine() ?: return null
        if (ready.generation != generation) return null
        return ParallelTranslationBenchmark.measure(engine, parallel)
    }
}

internal class DesktopTranslations(private val models: ModelHost<Path, out JobEngine>) {
    fun open(scope: CoroutineScope, options: TranslationStream.Options): TranslationStream =
        open(scope, options, persistent = null)

    fun open(scope: CoroutineScope, options: TranslationStream.Options, persistent: PersistentCache?): TranslationStream =
        TranslationStream(scope, {
            models.awaitEngine()?.let { (engine, ready) ->
                val origin = DesktopModelCatalog.entries.firstOrNull { it.filename == ready.model.fileName.toString() }?.origin
                TranslationStream.Backend(engine, origin ?: ModelOrigin(ready.model.toString(), 0), persistent)
            }
        }, options)
}
