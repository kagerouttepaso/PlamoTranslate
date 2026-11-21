package app.plamotranslate.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.LogSink
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.ModelHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.filechooser.FileNameExtensionFilter
import kotlin.system.exitProcess

fun main() {
    application {
        val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
        val owner = remember { java.util.concurrent.atomic.AtomicReference<java.awt.Window>() }
        val components = remember { assemble(scope) { owner.get() } }
        val audio by components.audio.state.collectAsState()
        Window(
            onCloseRequest = {
                components.audio.close()
                scope.cancel()
                exitApplication()
            },
            state = rememberWindowState(width = 1080.dp, height = 700.dp),
            title = "Mockingword",
        ) {
            LaunchedEffect(window) {
                owner.set(window)
                window.minimumSize = Dimension(760, 480)
            }
            DesktopTheme { MainScreen(components) }
        }
        SubtitleWindow(audio, components.audioPreferences) { components.audio.showSubtitle(false) }
    }
    exitProcess(0)
}

private fun assemble(scope: CoroutineScope, owner: () -> java.awt.Window?): DesktopComponents {
    Logs.install(listOf(LogSink { level, tag, message, error ->
        System.err.println("$tag $level: $message")
        error?.printStackTrace(System.err)
    }), userText = false)
    val directory = Path.of(checkNotNull(System.getenv("LOCALAPPDATA")), "Mockingword")
    val nativeDir = checkNotNull(System.getProperty("mockingword.native.dir"))
    val settings = DesktopSettings(directory)
    val loader = DesktopEngineLoader(nativeDir, !java.lang.Boolean.getBoolean("mockingword.cpuOnly"), settings)
    val models = ModelHost(
        CoroutineScope(scope.coroutineContext + Dispatchers.Default), loader, { settings.model },
        { TranslationEngine.DEFAULT_PARALLEL },
    )
    val translations = DesktopTranslations(models)
    val audioPreferences = AudioPreferences(directory)
    return DesktopComponents(
        NavigationPresenter(),
        TextPresenter(scope, models.state, translations) { text ->
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
        },
        SettingsPresenter(scope, settings, models) { current ->
            val chooser = JFileChooser(current.parent.toFile()).apply {
                dialogTitle = "翻訳モデルを選ぶ"
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("GGUF モデル (*.gguf)", "gguf")
            }
            if (chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION) {
                chooser.selectedFile.toPath().toAbsolutePath().normalize()
            } else null
        },
        AudioPresenter(
            scope, WindowsAudio, DesktopAsr(directory.resolve("models/asr").toFile())::load, translations::open,
            initialSelection = audioPreferences.loadSelection(), saveSelection = audioPreferences::saveSelection,
        ),
        audioPreferences,
    )
}

internal class DesktopComponents(
    val navigation: NavigationPresenter,
    val text: TextPresenter,
    val settings: SettingsPresenter,
    val audio: AudioPresenter,
    val audioPreferences: AudioPreferences,
)

internal enum class Page(val label: String) {
    AUDIO("音声翻訳"), PDF("PDF翻訳"), TEXT("テキスト翻訳"), SETTINGS("設定"),
}

internal class NavigationPresenter {
    private val selected = MutableStateFlow(Page.TEXT)
    val state = selected.asStateFlow()

    fun select(page: Page) {
        selected.value = page
    }
}
