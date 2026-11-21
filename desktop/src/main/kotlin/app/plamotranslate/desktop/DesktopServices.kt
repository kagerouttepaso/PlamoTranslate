package app.plamotranslate.desktop

import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.ParallelTranslationBenchmark
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.EngineLoader
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Locale
import java.util.Properties
import kotlin.io.path.isRegularFile

internal class DesktopSettings(private val directory: Path) {
    private val file = directory.resolve("settings.properties")
    val defaultModel: Path = directory.resolve("models/plamo-3-translate-0.8b-Q4_K_M.gguf")

    @Volatile private var properties = Properties()
    val model: Path get() = properties.getProperty("model")?.let { Path.of(it) } ?: defaultModel
    val threads: Int get() = properties.getProperty("threads")?.toIntOrNull()?.coerceAtLeast(0) ?: 0

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

    override suspend fun load(model: Path, parallel: Int): TranslationEngine = try {
        TranslationEngine.load(model.toFile(), nativeDir, preferGpu, cpu = CpuOptions(threads = settings.threads)).also {
            it.parallel = parallel
        }
    } catch (error: LinkageError) {
        Logs.e("Windows の翻訳ライブラリを読み込めませんでした", error)
        throw IllegalStateException("翻訳ライブラリを読み込めません。DLL の配置を確認して再起動してください。", error)
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
        TranslationStream(scope, {
            models.awaitEngine()?.let { (engine, ready) ->
                TranslationStream.Backend(engine, ModelOrigin(ready.model.toString(), 0))
            }
        }, options)
}
