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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.pipeline.compute.ThreadTrialProgress
import app.plamotranslate.pipeline.compute.ThreadTrialRow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
internal fun ThreadTrialDialog() {
    val owner = LocalLifecycleOwner.current
    val progress by produceState<ThreadTrialProgress?>(null, owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            value = null
            ProcessCompute.trialProgress.collectLatest { next ->
                if (next == null && value?.selected != null) delay(700)
                value = next
            }
        }
    }
    var closedId by rememberSaveable { mutableStateOf<String?>(null) }
    val trial = progress ?: return
    if (closedId == trial.id) return
    val longest = trial.rows.filterIsInstance<ThreadTrialRow.Measured>().maxOfOrNull { it.nanos } ?: 0L
    val accent = MaterialTheme.colorScheme.primary
    AlertDialog(
        onDismissRequest = { closedId = trial.id },
        title = { Text("この端末に合わせて速さを測っています（初回だけ）") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(trial.purpose.label, style = MaterialTheme.typography.titleMedium)
                trial.rows.forEachIndexed { index, row ->
                    val selected = trial.selected == index + 1
                    Row(Modifier.fillMaxWidth().alpha(if (row == ThreadTrialRow.CutOff) 0.55f else 1f),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("スレッド数 ${index + 1}")
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
        confirmButton = { TextButton(onClick = { closedId = trial.id }) { Text("閉じる") } },
    )
}
