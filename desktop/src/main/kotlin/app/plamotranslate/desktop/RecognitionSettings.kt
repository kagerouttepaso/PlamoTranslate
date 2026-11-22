package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.speech.SpeechModel

@Composable
internal fun RecognitionSettings(
    state: AudioState,
    presenter: AudioPresenter,
    models: SettingsState,
    modelPresenter: SettingsPresenter,
) {
    val settings = state.selection.recognition
    val change = presenter::setRecognition
    val enabled = state.canConfigure && models.download == null
    var expanded by remember { mutableStateOf(false) }
    Text("音声認識", style = MaterialTheme.typography.titleLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SpeechModel.entries.forEach { model ->
            FilterChip(selected = model == settings.model, enabled = enabled,
                onClick = { change(settings.copy(model = model)) }, label = { Text(model.label) })
        }
    }
    Text("Qwen3-ASR の話者自動判定には Whisper tiny が必要です。",
        style = MaterialTheme.typography.bodySmall)
    val files = DesktopSpeechModels.downloadFiles(settings.model)
    if (files.any { models.availableBytes(it) < it.size }) {
        OutlinedButton(
            onClick = { modelPresenter.prepareModels(recognition = settings.model) },
            enabled = enabled && !models.busy,
        ) { Text(modelDownloadLabel(files, models)) }
    }
    if (!state.canConfigure) Text("音声入力を停止すると設定を変更できます。")
    if (state.canExport) Text("認識設定を変える前に、必要な会話の記録を保存してください。")
    TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "音声認識の詳細を閉じる" else "音声認識の詳細") }
    if (expanded) {
        val defaults = DesktopRecognitionSettings()
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            CountMenu("書き起こしのスレッド数", settings.transcribeThreads.takeIf { it > 0 }, "自動", 1..16, enabled) {
                change(settings.copy(transcribeThreads = it ?: defaults.transcribeThreads))
            }
            if (!settings.model.identifiesLanguage) {
                CountMenu("言語識別のスレッド数", settings.identifyThreads, "既定（${defaults.identifyThreads}）", 1..8, enabled) {
                    change(settings.copy(identifyThreads = it ?: defaults.identifyThreads))
                }
            }
            CountMenu("話者を判定する音の長さ（ms）", (settings.cardLanguageSeconds * 1000).toInt(), "既定",
                listOf(300, 500, 750, 1000, 1500, 2000, 3000), enabled) {
                change(settings.copy(cardLanguageSeconds = it?.div(1000f) ?: defaults.cardLanguageSeconds))
            }
            CountMenu("音声検出のしきい値（%）", (settings.vadThreshold * 100).toInt(), "既定", 10..90 step 10, enabled) {
                change(settings.copy(vadThreshold = it?.div(100f) ?: defaults.vadThreshold))
            }
            CountMenu("発話とみなす最短の長さ（ms）", (settings.vadMinSpeechSeconds * 1000).toInt(), "既定",
                listOf(50, 100, 150, 250, 500, 750, 1000), enabled) {
                change(settings.copy(vadMinSpeechSeconds = it?.div(1000f) ?: defaults.vadMinSpeechSeconds))
            }
        }
        TextButton(onClick = { change(defaults.copy(model = settings.model)) }, enabled = enabled) { Text("調整を既定に戻す") }
    }
}
