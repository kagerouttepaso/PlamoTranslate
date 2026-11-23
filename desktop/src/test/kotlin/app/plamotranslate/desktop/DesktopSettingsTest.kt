package app.plamotranslate.desktop

import app.plamotranslate.pipeline.diagnostics.InferenceRole
import app.plamotranslate.llm.TranslationEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

class DesktopSettingsTest {
    @get:Rule val folder = TemporaryFolder()

    @Test
    fun `旧退避を翻訳だけへ移し再試行で別役割とCPU限定を変えない`() {
        val directory = folder.root.toPath()
        val file = directory.resolve("settings.properties")
        Files.writeString(file, "gpuFallback=driver\ncpuOnly=true\n")
        val settings = DesktopSettings(directory).apply { read() }
        assertFalse(Files.readString(file).contains("gpuFallback="))
        assertNull(settings.gpuFallback(InferenceRole.Transcription))
        assertTrue(settings.beginGpuLoad(InferenceRole.Transcription))
        settings.endGpuLoad(InferenceRole.Transcription, "ASR")
        settings.retryGpu(InferenceRole.Translation)
        val restarted = DesktopSettings(directory).apply { read() }
        assertNull(restarted.gpuFallback(InferenceRole.Translation))
        assertEquals("ASR", restarted.gpuFallback(InferenceRole.Transcription))
        assertTrue(restarted.cpuOnly)
    }

    @Test
    fun `詳しいログは既定でオフになり選択を保存できる`() {
        val directory = folder.root.toPath()
        val first = DesktopSettings(directory).apply { read() }
        assertFalse(first.detailedLogs)
        first.saveDetailedLogs(true)

        val reloaded = DesktopSettings(directory).apply { read() }
        assertTrue(reloaded.detailedLogs)
        reloaded.saveDetailedLogs(false)
        assertFalse(DesktopSettings(directory).apply { read() }.detailedLogs)
    }

    @Test
    fun `Vulkanの準備が二度途切れると明示的に再試行するまでCPUを使う`() {
        val directory = folder.root.toPath()
        val file = directory.resolve("settings.properties")
        Files.writeString(file, "gpuLoadingPid=0\n")
        assertTrue(DesktopSettings(directory).apply { read() }.beginGpuLoad(InferenceRole.Translation))
        Files.writeString(file, Files.readString(file).replace(Regex("loadingPid=\\d+"), "loadingPid=0"))

        val restarted = DesktopSettings(directory).apply { read() }
        assertFalse(restarted.beginGpuLoad(InferenceRole.Translation))
        assertTrue(restarted.gpuFallback(InferenceRole.Translation).orEmpty().contains("2回"))
        assertFalse(DesktopSettings(directory).apply { read() }.beginGpuLoad(InferenceRole.Translation))
        restarted.retryGpu(InferenceRole.Translation)
        assertTrue(restarted.beginGpuLoad(InferenceRole.Translation))
        restarted.endGpuLoad(InferenceRole.Translation)
        assertNull(DesktopSettings(directory).apply { read() }.gpuFallback(InferenceRole.Translation))
    }

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

    @Test fun `性能を戻しても外観とGPUの退避理由を保つ`() {
        val directory = folder.root.toPath()
        Files.writeString(directory.resolve("settings.properties"), "theme=old-value\n")
        val settings = DesktopSettings(directory).apply { read() }
        assertEquals(ThemeMode.SYSTEM, settings.appearance)
        settings.saveTheme(ThemeMode.LIGHT)
        settings.saveCpuOptions(settings.cpuOptions.copy(spinWait = false, threadPool = false, mmap = false))
        settings.endGpuLoad(InferenceRole.Translation, "GPU 準備失敗")
        val reopened = DesktopSettings(directory).apply { read() }
        assertFalse(reopened.cpuOptions.mmap)
        assertFalse(reopened.cpuOptions.threadPool)
        assertFalse(reopened.cpuOptions.spinWait)
        reopened.resetPerformance()
        assertEquals(app.plamotranslate.llm.CpuOptions(), reopened.cpuOptions)
        assertEquals(ThemeMode.LIGHT, reopened.appearance)
        assertEquals("GPU 準備失敗", reopened.gpuFallback(InferenceRole.Translation))
    }
}
