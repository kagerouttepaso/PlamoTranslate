package app.plamotranslate.desktop

import app.plamotranslate.llm.TranslationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `既存のモデル設定を読み取りスレッドと並列数を保存できる`() {
        val directory = folder.root.toPath()
        Files.writeString(directory.resolve("settings.properties"), "model=old-model.gguf\n")
        val settings = DesktopSettings(directory).apply { read() }
        assertEquals("old-model.gguf", settings.model.toString())
        assertEquals(0, settings.threads)
        assertEquals(TranslationEngine.DEFAULT_PARALLEL, settings.automaticParallel(settings.model, gpu = false))

        settings.saveThreads(6)
        settings.saveTunedParallel(settings.model, gpu = false, count = 2)
        val reloaded = DesktopSettings(directory).apply { read() }
        assertEquals(settings.model, reloaded.model)
        assertEquals(6, reloaded.threads)
        assertEquals(2, reloaded.automaticParallel(reloaded.model, gpu = false))
        assertEquals(TranslationEngine.DEFAULT_PARALLEL, reloaded.automaticParallel(reloaded.model, gpu = true))
    }

    @Test
    fun `モデルを変えても前のモデルの調整結果と手動設定を保つ`() {
        val directory = folder.root.toPath()
        val settings = DesktopSettings(directory)
        val first = settings.defaultModel
        val second = directory.resolve("other.gguf")
        settings.saveTunedParallel(first, gpu = true, count = 8)
        settings.saveManualParallel(first, gpu = true, count = 1)
        settings.save(second)

        val reloaded = DesktopSettings(directory).apply { read() }
        assertEquals(second, reloaded.model)
        assertEquals(TranslationEngine.DEFAULT_PARALLEL, reloaded.automaticParallel(second, gpu = true))
        assertEquals(8, reloaded.automaticParallel(first, gpu = true))
        assertEquals(1, reloaded.manualParallel(first, gpu = true))
        reloaded.saveManualParallel(first, gpu = true, count = null)
        assertNull(reloaded.manualParallel(first, gpu = true))
        assertEquals(8, reloaded.automaticParallel(first, gpu = true))
    }
}
