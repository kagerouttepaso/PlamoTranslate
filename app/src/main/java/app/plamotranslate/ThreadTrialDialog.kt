package app.plamotranslate

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.pipeline.compute.ThreadTrialRow

private fun componentLabel(component: String) = when (component) {
    "Qwen3-ASR", "SenseVoice" -> "音声認識"
    "Whisper" -> "言語の判定"
    "PLaMo" -> "翻訳"
    "Supertonic" -> "読み上げ"
    "PP-OCR", "ML Kit" -> "文字認識"
    else -> component
}

@Composable
internal fun ThreadTrialDialog() {
    val progress by ProcessCompute.trialProgress.collectAsStateWithLifecycle()
    val trial = progress ?: return
    var closed by rememberSaveable(trial.component) { mutableStateOf(false) }
    if (closed) return
    val longest = trial.rows.filterIsInstance<ThreadTrialRow.Measured>().maxOfOrNull { it.nanos } ?: 0L
    val accent = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = { closed = true },
        title = { Text("この端末に合わせて速さを測っています（初回だけ）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(componentLabel(trial.component), style = MaterialTheme.typography.titleMedium)
                trial.rows.forEachIndexed { index, row ->
                    val selected = trial.selected == index + 1
                    Row(Modifier.fillMaxWidth().alpha(if (row == ThreadTrialRow.CutOff) 0.55f else 1f),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("${index + 1} コア")
                        Box(Modifier.weight(1f)) {
                            if (row is ThreadTrialRow.Measured && longest > 0) {
                                Box(Modifier.fillMaxWidth(row.nanos.toFloat() / longest).height(9.dp)
                                    .background(if (selected) accent else MaterialTheme.colorScheme.secondary))
                            }
                        }
                        Text(when (row) {
                            ThreadTrialRow.Pending -> "—"
                            ThreadTrialRow.CutOff -> "打ち切り"
                            ThreadTrialRow.Measuring -> "測定中…"
                            is ThreadTrialRow.Measured -> "%.2f秒".format(row.nanos / 1e9)
                        })
                        if (selected) Text("選択", color = accent)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { closed = true }) { Text("閉じる") } },
    )
}
