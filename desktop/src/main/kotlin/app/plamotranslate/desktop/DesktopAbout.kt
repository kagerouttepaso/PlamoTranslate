package app.plamotranslate.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path

private const val PROJECT = "https://github.com/kagerouttepaso/PlamoTranslate"
internal const val PLAMO_LICENSE = "https://plamo.preferredai.jp/info/plamo-community-license-ja"

@Composable
internal fun AboutDesktop() {
    val links = LocalUriHandler.current
    Text("$PRODUCT_NAME ${System.getProperty("mockingword.version", "開発版")}", style = MaterialTheme.typography.titleMedium)
    Text("Built with PLaMo")
    Text("このアプリは Preferred Networks の公式製品ではありません。")
    Text("入力した文、音声、PDF はこの PC で処理します。モデルと PDF 用フォントは初めて使うときに配布元から取得します。")
    Text("PDF の翻訳キャッシュとログは PC に保存します。ログに原文と訳文を含めるかは「記録」で選べます。")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        DesktopOutline({ links.openUri("$PROJECT/releases") }) { Text("配布版と更新") }
        DesktopLink({ links.openUri(PROJECT) }) { Text("ソースコード") }
    }
}

private data class ModelLicense(val name: String, val author: String, val license: String, val url: String)
private val modelLicenses = listOf(
    ModelLicense("PLaMo 3 Translate", "Preferred Networks", "PLaMo Community License", PLAMO_LICENSE),
    ModelLicense("SenseVoice Small", "FunAudioLLM", "FunASR Model Open Source License Agreement", "https://huggingface.co/FunAudioLLM/SenseVoiceSmall"),
    ModelLicense("Qwen3-ASR 0.6B", "Qwen", "Apache License 2.0", "https://huggingface.co/Qwen/Qwen3-ASR-0.6B"),
    ModelLicense("Whisper tiny", "OpenAI", "MIT", "https://github.com/openai/whisper/blob/main/LICENSE"),
    ModelLicense("Silero VAD", "Silero Team", "MIT", "https://github.com/snakers4/silero-vad/blob/master/LICENSE"),
    ModelLicense("Noto Sans JP", "The Noto Project", "SIL Open Font License 1.1", "https://github.com/google/fonts/blob/main/ofl/notosansjp/OFL.txt"),
)

@Composable
internal fun DesktopLicenses() {
    val links = LocalUriHandler.current
    var showTexts by remember { mutableStateOf(false) }
    Text("配布物に含まれるソフトウェア", style = MaterialTheme.typography.titleMedium)
    Text("Mockingword・llama.cpp / ggml・ONNX Runtime（MIT）、sherpa-onnx・Compose・Kotlin・PDFBox（Apache License 2.0）、PDFium（BSD 系）、HarfBuzz、Java 実行環境と各ライブラリの第三者表示。")
    DesktopOutline({ showTexts = true }) { Text("同梱のライセンス全文") }
    Text("ダウンロードするモデルとフォント", style = MaterialTheme.typography.titleMedium)
    modelLicenses.forEach { entry ->
        Column {
            Text("${entry.name} · ${entry.author}")
            DesktopLink({ links.openUri(entry.url) }) { Text(entry.license) }
        }
    }
    if (showTexts) LicenseTexts { showTexts = false }
}

@Composable
private fun LicenseTexts(close: () -> Unit) {
    val roots = remember { listOf(
        Path.of(System.getProperty("compose.application.resources.dir"), "licenses"),
        Path.of(System.getProperty("java.home"), "legal"),
    ) }
    val files by produceState<List<Path>?>(null) {
        value = withContext(Dispatchers.IO) {
            roots.filter { Files.isDirectory(it) }.flatMap { root ->
                Files.walk(root).use { stream -> stream.filter { Files.isRegularFile(it) }.sorted().toList() }
            }
        }
    }
    var file by remember { mutableStateOf<Path?>(null) }
    Dialog(onDismissRequest = close) {
        Surface(Modifier.fillMaxWidth().fillMaxHeight(0.82f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(DesktopSize.margin), verticalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                Row(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                    if (file != null) DesktopLink({ file = null }) { Text("一覧に戻る") }
                    Spacer(Modifier.weight(1f))
                    DesktopLink(close) { Text("閉じる") }
                }
                val selected = file
                Text(selected?.fileName?.toString() ?: "ライセンス全文", style = MaterialTheme.typography.titleMedium)
                if (selected == null) {
                    val entries = files
                    if (entries == null) Text("読み込み中…") else LazyColumn {
                        items(entries) { path ->
                            val root = roots.first(path::startsWith)
                            DesktopLink({ file = path }) { Text("${root.fileName} / ${root.relativize(path)}") }
                        }
                    }
                } else {
                    val lines by produceState<List<String>?>(null, selected) {
                        value = withContext(Dispatchers.IO) {
                            try { selected.toFile().bufferedReader().use { it.readLines() } }
                            catch (error: java.io.IOException) { listOf("読み込めませんでした：${error.localizedMessage}") }
                        }
                    }
                    val content = lines
                    if (content == null) Text("読み込み中…") else SelectionContainer {
                        LazyColumn { items(content) { Text(it, style = MaterialTheme.typography.bodySmall) } }
                    }
                }
            }
        }
    }
}
