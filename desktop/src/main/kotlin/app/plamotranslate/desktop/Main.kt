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
import app.plamotranslate.llm.Language
import app.plamotranslate.log.LogSink
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.translation.ModelHost
import app.plamotranslate.pipeline.translation.ModelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.awt.Dimension
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.nio.file.Files
import java.nio.file.Path
import javax.swing.JFileChooser
import javax.swing.JOptionPane
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

/**
 * JNI の DLL が依存する DLL を、先に完全なパスで読み込む。Windows は依存先を java.library.path から探さないので、
 * 配布物（DLL を PATH に足さずに起動する）では、これをしないと読み込みに失敗する。
 */
private fun preloadDependencies(nativeDir: String) {
    listOf("ggml-base.dll", "ggml.dll", "llama.dll", "pdfium.dll").forEach { name ->
        val file = java.io.File(nativeDir, name)
        if (file.isFile) System.load(file.absolutePath)
    }
}

private fun assemble(scope: CoroutineScope, owner: () -> java.awt.Window?): DesktopComponents {
    val directory = Path.of(checkNotNull(System.getenv("LOCALAPPDATA")), "Mockingword")
    Logs.install(listOf(LogSink { level, tag, message, error ->
        System.err.println("$tag $level: $message")
        error?.printStackTrace(System.err)
    }, DesktopLogFile(directory.resolve("logs"))), userText = false)
    val nativeDir = checkNotNull(System.getProperty("mockingword.native.dir"))
    preloadDependencies(nativeDir)
    val settings = DesktopSettings(directory)
    val loader = DesktopEngineLoader(nativeDir, !java.lang.Boolean.getBoolean("mockingword.cpuOnly"), settings)
    val models = ModelHost(
        CoroutineScope(scope.coroutineContext + Dispatchers.Default), loader, { settings.model },
        { TranslationEngine.DEFAULT_PARALLEL },
    )
    val translations = DesktopTranslations(models)
    val pdfCache = DesktopPdfCache(directory.resolve("cache/pdf-translations/v1"))
    val audioPreferences = AudioPreferences(directory)
    val asr = DesktopAsr(directory.resolve("models/asr").toFile())
    val copy: (String) -> Unit = { value ->
        Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
    }
    val text = TextPresenter(scope, models.state, translations::open,
        paste = {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) clipboard.getData(DataFlavor.stringFlavor) as? String else null
        }, copy = copy, voiceInput = DesktopTextVoice(WindowsAudio, asr::load),
        recognition = { audioPreferences.loadSelection().recognition }, saveTarget = settings::selectTranslationTarget)
    val pdf = PdfPresenter(
        scope, DesktopPdfReader(scope), { childScope, options -> translations.open(childScope, options, pdfCache) },
        chooseFile = {
            val chooser = JFileChooser().apply {
                dialogTitle = "PDF を開く"
                isAcceptAllFileFilterUsed = false
                fileFilter = FileNameExtensionFilter("PDF (*.pdf)", "pdf")
            }
            if (chooser.showOpenDialog(owner()) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
        },
        chooseSave = { filename -> chooseSavePath("訳した PDF を保存", filename, "PDF (*.pdf)", "pdf", owner()) },
        preferences = PdfViewPreferences(directory),
    )
    scope.launch {
        models.state.collect { state -> if (state is ModelState.Ready) pdf.setParallel(state.parallel) }
    }
    scope.launch {
        settings.translationTarget.collect { target ->
            if (text.state.value.target != target) text.selectTarget(target)
            pdf.selectTarget(target)
        }
    }
    return DesktopComponents(
        NavigationPresenter(),
        text,
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
            scope, WindowsAudio, asr::load, translations::open,
            initialSelection = audioPreferences.loadSelection(), saveSelection = audioPreferences::saveSelection,
            supportsAutomatic = asr::supportsAutomatic,
            chooseLog = { filename -> chooseConversationLog(filename, owner()) },
            copyText = copy,
        ),
        pdf,
        audioPreferences,
        settings::selectTranslationTarget,
    )
}

private fun chooseConversationLog(filename: String, owner: java.awt.Window?): Path? =
    chooseSavePath("会話の記録を保存", filename, "Markdown (*.md)", "md", owner)

/** 保存先を選ばせる。拡張子が無ければ足し、既にあるファイルは上書きしてよいかを確かめる。 */
private fun chooseSavePath(title: String, filename: String, description: String, extension: String, owner: java.awt.Window?): Path? {
    val chooser = JFileChooser().apply {
        dialogTitle = title
        fileFilter = FileNameExtensionFilter(description, extension)
        selectedFile = java.io.File(currentDirectory, filename)
    }
    if (chooser.showSaveDialog(owner) != JFileChooser.APPROVE_OPTION) return null
    val selected = chooser.selectedFile
    val path = if (selected.name.endsWith(".$extension", ignoreCase = true)) selected.toPath()
        else selected.toPath().resolveSibling("${selected.name}.$extension")
    return path.takeIf {
        !Files.exists(it) || JOptionPane.showConfirmDialog(owner, "${it.fileName} を上書きしますか？",
            "上書きの確認", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION
    }
}

internal class DesktopComponents(
    val navigation: NavigationPresenter,
    val text: TextPresenter,
    val settings: SettingsPresenter,
    val audio: AudioPresenter,
    val pdf: PdfPresenter,
    val audioPreferences: AudioPreferences,
    val selectTranslationTarget: (Language) -> Unit,
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
