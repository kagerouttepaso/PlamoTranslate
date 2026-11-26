package app.plamotranslate

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * GPU バックエンド（Vulkan / Vulkan coopmat無効 / OpenCL / CPU）を別プロセスで一通り試して比べるダイアログ。
 * 初回起動時と、設定の「性能」から開ける。
 */
@Composable
internal fun GpuProbeDialog(
    initialPrompt: Boolean,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var current by remember { mutableStateOf<GpuBackendChoice?>(null) }
    var results by remember { mutableStateOf<List<GpuProbeItem>>(emptyList()) }
    var finished by remember { mutableStateOf(false) }

    fun startProbe() {
        running = true
        finished = false
        results = emptyList()
        scope.launch {
            val all = GpuProbeRunner.runAll(context) { step, done ->
                current = step
                results = done
            }
            results = all
            current = null
            running = false
            finished = true
        }
    }

    val best = remember(results, finished) {
        if (!finished) null
        else results.filter { it.ok }.maxByOrNull { it.parallelTps.takeIf { v -> v > 0 } ?: it.singleTps }
    }

    AlertDialog(
        onDismissRequest = { if (!running) onDismiss() },
        title = { Text("GPU バックエンドを比べる") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!running && !finished) {
                    Text(
                        if (initialPrompt) {
                            "この端末で使える GPU バックエンド（Vulkan・Vulkan coopmat無効・OpenCL・CPU）を一通り試して速さを比べられます。" +
                                "別プロセスで1つずつ試すため、非対応でもアプリは落ちません。あとで設定の「性能」からも試せます。"
                        } else {
                            "Vulkan・Vulkan（coopmat無効）・OpenCL・CPU を別プロセスで1つずつ試し、読込時間・生成速度・4並列の速さを比べます（1〜2分）。"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (running) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    Text(
                        "測定中：${current?.label ?: ""}（${results.size + 1} / ${GpuProbeRunner.CANDIDATES.size}）",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                    )
                }
                results.forEach { item ->
                    val isBest = best?.choice == item.choice
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (isBest) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceVariant,
                        ),
                    ) {
                        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Text(
                                item.choice.label + (if (isBest) "（最速）" else ""),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                            )
                            if (item.ok) {
                                Text(item.device, style = MaterialTheme.typography.labelSmall)
                                Text(
                                    "読込 ${item.loadMs}ms / 初回 ${item.firstMs}ms / 生成 ${"%.1f".format(item.singleTps)} tok/s / 4並列 ${"%.0f".format(item.parallelTps)} tok/s",
                                    style = MaterialTheme.typography.bodySmall,
                                )
                                if (item.sample.isNotEmpty()) {
                                    Text("訳: ${item.sample}", style = MaterialTheme.typography.labelSmall)
                                }
                                if (finished) {
                                    OutlinedButton(
                                        onClick = {
                                            TranslationPreferences(context).gpuBackend = item.choice
                                            restartApp(context)
                                        },
                                        modifier = Modifier.padding(top = 4.dp),
                                    ) { Text("これを使う（再起動）") }
                                }
                            } else {
                                Text(
                                    item.error ?: "失敗",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
                if (finished && results.isNotEmpty()) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.copyToClipboard("GPU比較結果", GpuProbeRunner.formatReport(results))
                            Toast.makeText(context, "結果をコピーしました", Toast.LENGTH_SHORT).show()
                        }) { Text("結果をコピー") }
                    }
                }
            }
        },
        confirmButton = {
            if (!running && !finished) {
                TextButton(onClick = ::startProbe) { Text("一通り試す") }
            } else if (finished) {
                TextButton(onClick = onDismiss) { Text("閉じる") }
            }
        },
        dismissButton = {
            if (!running && !finished) {
                TextButton(onClick = onDismiss) { Text(if (initialPrompt) "あとで" else "閉じる") }
            }
        },
    )
}
