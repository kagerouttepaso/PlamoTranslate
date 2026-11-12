package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.plamotranslate.llm.CpuOptions
import app.plamotranslate.llm.TranslationEngine
import kotlinx.coroutines.launch

/*
 * パフォーマンスの設定（2026-11-08）。15T Pro（MediaTek Dimensity 9400+）で、アプリ内の翻訳の生成が
 * 0.3〜0.8トークン/秒しか出なかった（同じ端末のCLIでは52〜65トークン/秒）。原因の候補を切り替えて試せるようにし、
 * Snapdragon以外の端末の調整にも使えるよう、正式な設定として残す（作者の判断）。docs/03_翻訳コア.md「パフォーマンスの設定」。
 */

private val THREAD_CHOICES = listOf(0, 1, 2, 4, 6, 8)
private val PARALLEL_CHOICES = listOf(1, 2, 3, 4, 6, 8)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PerformanceCard(model: ModelState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val libDir = context.applicationInfo.nativeLibraryDir
    val variants = remember { TranslationEngine.cpuVariants(libDir) }
    // 起動時の値。版と「CPUだけ」は起動し直すまで効かないので、変えたら知らせる
    val preferences = remember { TranslationPreferences(context) }
    val startVariant = remember { preferences.cpuOptions.variant }
    val startCpuOnly = remember { preferences.cpuOnly }
    var options by remember { mutableStateOf(preferences.cpuOptions) }
    var cpuOnly by remember { mutableStateOf(preferences.cpuOnly) }
    var result by remember { mutableStateOf<String?>(null) }
    var measuring by remember { mutableStateOf(false) }
    var parallel by remember { mutableStateOf(preferences.parallel) }
    var tuning by remember { mutableStateOf<String?>(null) }
    val busy = model is ModelState.Loading || measuring
    val gpuDisabled = remember { GpuGuard.disabledNote(context) }

    fun apply(next: CpuOptions = options, nextCpuOnly: Boolean = cpuOnly) {
        options = next
        cpuOnly = nextCpuOnly
        result = null
        preferences.saveCpu(next, nextCpuOnly)
        EngineHolder.reload(context)
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("パフォーマンス", style = MaterialTheme.typography.titleMedium)
            Text(
                "翻訳が遅いときに、端末に合う組み合わせを探すための設定です。変えるとモデルを読み直します。" +
                    "「速さを測る」で比べてください。",
                style = MaterialTheme.typography.bodySmall,
            )
            val ready = model as? ModelState.Ready
            val backend = remember(ready) { TranslationEngine.cpuBackend() }
            Text(
                "今：${if (ready?.engine?.usesGpu == true) "GPU" else "CPU"}で動作" +
                    (if (backend.isNotEmpty()) "（${backend.removePrefix("libggml-cpu-").removeSuffix(".so")}）" else ""),
                style = MaterialTheme.typography.bodySmall,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !busy && ready != null, onClick = {
                    measuring = true
                    result = null
                    tuning = null
                    scope.launch {
                        // 1文の速さ（テキスト翻訳・会話翻訳の目安）と、今の「同時に訳す数」での合計の速さ（画面翻訳・カメラ翻訳の目安）を
                        // 両方出す。2026-11-12、作者の指摘「同時に訳す数を変えても、速さを測るの値が変わらず手動で比べられない」。
                        // 2行目は「自動で調整」と同じ測り方なので、数を変えて押せば手でも比べられる
                        val single = runCatching { EngineBenchmark.run(context) }.fold(
                            onSuccess = { b ->
                                b?.let {
                                    "1文：最初の文字まで ${it.firstMillis}ms、生成 ${"%.1f".format(it.tokensPerSecond)} トークン/秒" +
                                        "（${it.tokens}トークン、全体 ${it.totalMillis}ms）"
                                } ?: "1文：測れませんでした"
                            },
                            onFailure = { "1文：${it.message ?: it}" },
                        )
                        val n = parallel
                        val batch = runCatching { EngineBenchmark.runParallel(context, n) }.fold(
                            onSuccess = { rate -> rate?.let { "同時に${n}件：合計 ${"%.0f".format(it)} トークン/秒" } ?: "同時に${n}件：測れませんでした" },
                            onFailure = { "同時に${n}件：${it.message ?: it}" },
                        )
                        result = "$single\n$batch（「自動で調整」と同じ測り方）"
                        measuring = false
                    }
                }) { Text(if (measuring) "測っています…" else "速さを測る") }
                TextButton(enabled = !busy, onClick = {
                    parallel = TranslationEngine.DEFAULT_PARALLEL
                    tuning = null
                    // 同時に訳す数は読み直しの前に保存しておく（読み直したエンジンが保存した値を読む）
                    EngineHolder.setParallel(context, parallel)
                    apply(CpuOptions(), false)
                }) { Text("既定に戻す") }
            }
            result?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            gpuDisabled?.let { note ->
                Text(
                    "GPUで読み込む途中でアプリが終わったため、この端末ではCPUで動かしています（$note）。" +
                        "端末のGPUのドライバーがllama.cppのGPUの処理に合わないと起きます。" +
                        "読み込み中に強制停止しただけなら、もう一度試してください。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(enabled = !busy, onClick = {
                    GpuGuard.retry(context)
                    restartApp(context)
                }) { Text("GPUをもう一度試す（起動し直す）") }
            }

            Text("同時に訳す数", style = MaterialTheme.typography.titleSmall)
            Text(
                "画面翻訳とカメラ翻訳で、いくつの文のまとまりを一緒に訳すか。端末によって速い数が違います。" +
                    "「自動で調整」は3回測って一番合う数にします（十数秒）。手で比べるときは、数を選んで「速さを測る」の" +
                    "「同時に◯件」の値を見てください。値は原文の読み込みも含めた合計なので、1文の生成の速さとは別の数です。" +
                    "変えてもモデルは読み直しません。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PARALLEL_CHOICES.forEach { n ->
                    FilterChip(
                        selected = parallel == n,
                        enabled = !busy,
                        onClick = {
                            parallel = n
                            tuning = null
                            EngineHolder.setParallel(context, n)
                        },
                        label = { Text(if (n == 1) "1（1つずつ）" else "$n") },
                    )
                }
            }
            OutlinedButton(enabled = !busy && ready != null, onClick = {
                measuring = true
                scope.launch {
                    // 少ない手数で決める（ParallelTuner）。測るたびに、そこまでで一番合う数を入れる
                    val measured = LinkedHashMap<Int, Double>()
                    fun report(done: Boolean) = measured.entries.joinToString("、") { (n, rate) ->
                        "${n}本 ${if (rate > 0) "%.0f".format(rate) else "測れず"}"
                    } + "（合計のトークン/秒）" + if (done) " → ${parallel}本にしました" else " 測っています…"
                    while (true) {
                        val n = ParallelTuner.next(measured) ?: break
                        tuning = if (measured.isEmpty()) "測っています…" else report(false)
                        measured[n] = runCatching { EngineBenchmark.runParallel(context, n) }
                            .onFailure { android.util.Log.w("PlamoTranslate", "同時に訳す数の計測に失敗（${n}本）", it) }
                            .getOrNull() ?: 0.0
                        parallel = ParallelTuner.best(measured)
                        EngineHolder.setParallel(context, parallel)
                    }
                    tuning = report(true)
                    measuring = false
                }
            }) { Text("自動で調整") }
            tuning?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }

            Text("スレッド数", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                THREAD_CHOICES.forEach { n ->
                    FilterChip(
                        selected = options.threads == n,
                        enabled = !busy,
                        onClick = { apply(options.copy(threads = n)) },
                        label = { Text(if (n == 0) "自動（${TranslationEngine.defaultThreads()}）" else "$n") },
                    )
                }
            }
            SettingSwitch("性能ヒント", "OSにCPUの周波数・配置を上げてもらう（ADPF）", options.boost, !busy) {
                apply(options.copy(boost = it))
            }
            SettingSwitch("回して待つ", "オフにすると、スレッドが仕事を待つ間は眠る", options.spinWait, !busy) {
                apply(options.copy(spinWait = it))
            }
            SettingSwitch("スレッドを常駐させる", "オフにすると、llama.cppが推論のたびにスレッドを用意する", options.threadPool, !busy) {
                apply(options.copy(threadPool = it))
            }
            SettingSwitch("ファイルの写像で読む（mmap）", "オフにすると、モデルをメモリへ全部読み込む", options.mmap, !busy) {
                apply(options.copy(mmap = it))
            }
            SettingSwitch("CPUだけで動かす", "Snapdragonの端末でもGPUを使わない（起動し直すと効く）", cpuOnly, !busy) {
                apply(nextCpuOnly = it)
            }

            if (variants.isNotEmpty()) {
                Text("CPUの計算部分の版（起動し直すと効く）", style = MaterialTheme.typography.titleSmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = options.variant.isEmpty(),
                        enabled = !busy,
                        onClick = { apply(options.copy(variant = "")) },
                        label = { Text("自動") },
                    )
                    variants.forEach { v ->
                        FilterChip(
                            selected = options.variant == v,
                            enabled = !busy,
                            onClick = { apply(options.copy(variant = v)) },
                            label = { Text(v.removePrefix("libggml-cpu-").removeSuffix(".so")) },
                        )
                    }
                }
            }
            if (options.variant != startVariant || cpuOnly != startCpuOnly) {
                Text(
                    "版・「CPUだけ」の変更は、アプリを起動し直すと効きます。",
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
                OutlinedButton(onClick = { restartApp(context) }) { Text("アプリを起動し直す") }
            }
        }
    }
}

@Composable
private fun SettingSwitch(title: String, note: String, checked: Boolean, enabled: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, enabled = enabled, onCheckedChange = onChange)
    }
}

/** トップ画面を開き直してからプロセスを終える。計算部分のライブラリは起動ごとに1回だけ読み込むため。 */
private fun restartApp(context: android.content.Context) {
    // 読み込みの途中で起動し直しても、GPUで落ちたとみなさない
    GpuGuard.forgetPending(context)
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName) ?: return
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
    context.startActivity(intent)
    android.os.Process.killProcess(android.os.Process.myPid())
}
