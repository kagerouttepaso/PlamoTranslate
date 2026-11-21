package app.plamotranslate.desktop

import app.plamotranslate.llm.CpuOptions
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
import java.util.Properties
import kotlin.io.path.isRegularFile

internal class DesktopSettings(private val directory: Path) {
    private val file = directory.resolve("settings.properties")
    val defaultModel: Path = directory.resolve("models/plamo-3-translate-0.8b-Q4_K_M.gguf")

    @Volatile
    var model: Path = defaultModel
        private set

    fun read() {
        if (!Files.exists(file)) return
        val values = Properties()
        Files.newInputStream(file).use(values::load)
        values.getProperty("model")?.takeIf(String::isNotBlank)?.let { model = Path.of(it) }
    }

    fun save(path: Path) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "settings-", ".tmp")
        try {
            val values = Properties().apply { setProperty("model", path.toString()) }
            Files.newOutputStream(temp).use { values.store(it, null) }
            Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            model = path
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}

internal class DesktopEngineLoader(private val nativeDir: String, private val preferGpu: Boolean) :
    EngineLoader<Path, TranslationEngine> {
    override fun missing(model: Path): String? = model.toString().takeUnless { model.isRegularFile() }

    override suspend fun load(model: Path, parallel: Int): TranslationEngine = try {
        val threads = (Runtime.getRuntime().availableProcessors() / 2).coerceAtLeast(1)
        TranslationEngine.load(model.toFile(), nativeDir, preferGpu, cpu = CpuOptions(threads = threads)).also {
            it.parallel = parallel
        }
    } catch (error: LinkageError) {
        Logs.e("Windows の翻訳ライブラリを読み込めませんでした", error)
        throw IllegalStateException("翻訳ライブラリを読み込めません。DLL の配置を確認して再起動してください。", error)
    }

    override fun usesGpu(engine: TranslationEngine): Boolean = engine.usesGpu

    override fun setParallel(engine: TranslationEngine, parallel: Int) {
        engine.parallel = parallel
    }

    override suspend fun close(engine: TranslationEngine) = engine.close()
}

internal class DesktopTranslations(private val models: ModelHost<Path, out JobEngine>) {
    fun open(scope: CoroutineScope, options: TranslationStream.Options): TranslationStream =
        TranslationStream(scope, {
            models.awaitEngine()?.let { (engine, ready) ->
                TranslationStream.Backend(engine, ModelOrigin(ready.model.toString(), 0))
            }
        }, options)
}
