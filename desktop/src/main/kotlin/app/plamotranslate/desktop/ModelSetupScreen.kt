package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.models.ModelFile
import app.plamotranslate.pipeline.speech.SpeechModel

@Composable
internal fun ModelSetupScreen(
    state: SettingsState,
    presenter: SettingsPresenter,
    defaultRecognition: SpeechModel,
    initial: Boolean,
) {
    var translation by remember {
        mutableStateOf(DesktopModelCatalog.entries.firstOrNull { it.path(state.defaultPath.parent) == state.selectedPath }
            ?: DesktopModelCatalog.SMALL)
    }
    var recognition by remember(defaultRecognition) { mutableStateOf(defaultRecognition) }
    val files = listOfNotNull(translation.file.takeIf { initial }) + DesktopSpeechModels.downloadFiles(recognition)

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(if (initial) DesktopSize.margin else 0.dp),
        verticalArrangement = Arrangement.spacedBy(DesktopSize.gap),
    ) {
        Text("モデルの準備", style = MaterialTheme.typography.headlineSmall)
        if (!initial) Text("音声翻訳を使うには、音声認識モデルを準備してください。")
        if (initial) {
            ModelChoice("翻訳モデル", DesktopModelCatalog.entries, translation, !state.busy, { translation = it }) {
                "${it.title}（約${it.size / 1_000_000} MB）"
            }
        }
        ModelChoice("音声認識モデル", SpeechModel.entries, recognition, !state.busy, { recognition = it }) {
            val name = if (it.identifiesLanguage) it.label else "${it.label}（Whisper tiny 付き）"
            val size = DesktopSpeechModels.downloadFiles(it).sumOf(ModelFile::size)
            "$name（約${size / 1_000_000} MB）"
        }
        Text("音声認識には Silero VAD も準備します。", style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val download = state.download
        if (download == null) {
            DesktopButton(
                onClick = { presenter.prepareModels(translation.takeIf { initial }, recognition) },
                enabled = !state.busy,
            ) { Text(modelDownloadLabel(files, state)) }
            if (initial) {
                DesktopLink(onClick = presenter::choose, enabled = !state.busy) { Text("GGUF ファイルを選ぶ") }
                TranslationModelLicense()
            }
        } else {
            ModelDownloadControls(download, presenter::cancelDownload)
        }
    }
}

@Composable
private fun TranslationModelLicense() {
    val links = LocalUriHandler.current
    Text(
        "PLaMo 3 Translate をダウンロードして使うと、PLaMo Community License に同意したことになります。" +
            "18歳未満の方は、保護者の同意を得てから使ってください。個人の利用と非商用の利用は無料です。",
        style = MaterialTheme.typography.bodySmall,
    )
    DesktopLink(onClick = { links.openUri(PLAMO_LICENSE) }) {
        Text("ライセンスを読む")
    }
    Text("Built with PLaMo", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun <T> ModelChoice(
    title: String,
    options: List<T>,
    selected: T,
    enabled: Boolean,
    onSelect: (T) -> Unit,
    label: (T) -> String,
) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        options.forEach { option ->
            DesktopChoice(
                selected = option == selected, enabled = enabled,
                onClick = { onSelect(option) }, label = { Text(label(option)) },
            )
        }
    }
}

internal fun modelDownloadLabel(files: List<ModelFile>, state: SettingsState): String {
    val remaining = files.sumOf { it.size - state.availableBytes(it) }
    if (remaining == 0L) return "選んだモデルを使う"
    val size = if (remaining < 1_000_000) "1 MB 未満" else "約${remaining / 1_000_000} MB"
    val action = if (files.any { state.availableBytes(it) in 1 until it.size }) "ダウンロードを再開" else "ダウンロード"
    return "$action（$size）"
}
