package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.translation.ModelState

@Composable
internal fun ModelSettings(state: SettingsState, presenter: SettingsPresenter) {
    val enabled = !state.busy && state.model !is ModelState.Loading
    DesktopModelCatalog.entries.forEach { model ->
        val bytes = state.availableBytes(model.file)
        val selected = state.selectedPath == model.path(state.defaultPath.parent)
        val action = when {
            bytes == model.size -> "使う"
            bytes > 0 -> "ダウンロードを再開"
            else -> "ダウンロードして使う"
        }
        Row(Modifier.fillMaxWidth(), Arrangement.spacedBy(DesktopSize.gap), Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(model.title + if (selected) "（選択中）" else "")
                val size = "${model.size / 1_000_000} MB"
                Text(if (bytes in 1 until model.size) "${bytes / 1_000_000} / $size" else size,
                    style = MaterialTheme.typography.bodySmall)
            }
            DesktopOutline(onClick = { presenter.prepareModels(translation = model) }, enabled = enabled) { Text(action) }
        }
    }
    var details by remember { mutableStateOf(false) }
    DesktopLink(onClick = { details = !details }) { Text(if (details) "詳細を閉じる" else "モデルの詳細") }
    if (details) {
        Text(state.selectedPath.toString(), style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
            DesktopOutline(onClick = presenter::choose, enabled = enabled) { Text("GGUF ファイルを選ぶ") }
            DesktopOutline(onClick = presenter::reload, enabled = enabled) { Text("読み込み直す") }
        }
    }
}

@Composable
internal fun ModelDownloadControls(progress: ModelPreparationProgress, cancel: () -> Unit) {
    val file = progress.file
    if (file == null) {
        Text("モデルを確認中…")
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    } else {
        val fraction = (progress.bytes.toDouble() / progress.total).toFloat().coerceIn(0f, 1f)
        val label = if (progress.verifying) "SHA-256 を照合中" else "ダウンロード中 ${(fraction * 100).toInt()}%"
        Text("${file.label}：$label")
        LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
    }
    DesktopOutline(onClick = cancel) { Text("取り消す") }
}

@Composable
internal fun BackendSettings(state: SettingsState, presenter: SettingsPresenter) {
    val enabled = !state.busy && state.model !is ModelState.Loading
    Row(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        DesktopOutline(onClick = { presenter.setCpuOnly(false) }, enabled = enabled && state.cpuOnly) { Text("Vulkan を優先") }
        DesktopOutline(onClick = { presenter.setCpuOnly(true) }, enabled = enabled && !state.cpuOnly) { Text("CPU だけで動かす") }
    }
    Text("実行先の設定：${if (state.cpuOnly) "CPU" else "Vulkan 優先"}", style = MaterialTheme.typography.bodySmall)
    state.gpuFallback?.let {
        Text("CPU に切り替えました：$it")
        DesktopOutline(onClick = presenter::retryGpu, enabled = enabled) { Text("GPU をもう一度試す") }
    }
}
