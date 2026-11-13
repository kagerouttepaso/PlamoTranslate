package app.plamotranslate.diagnostics

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.pipeline.diagnostics.CrashArchive
import app.plamotranslate.pipeline.diagnostics.CrashReports
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 設定の「不具合の記録」のページ（[Diagnostics]）。記録の一覧を出し、zip に書き出す・全部消す。
 * 書き出しはファイルの保存先を本人が選ぶ（Android のファイルの保存の画面）。アプリからどこかへ送ることはない。
 */
@Composable
fun DiagnosticsSettings() {
    val context = LocalContext.current
    val journal = remember { Diagnostics.journal(context) }
    val entries by journal.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        exporting = true
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { context.contentResolver.openOutputStream(uri)!!.use { Diagnostics.export(context, it) } }
            }
            exporting = false
            result
                .onSuccess { Toast.makeText(context, "不具合の記録を保存しました", Toast.LENGTH_SHORT).show() }
                .onFailure { Toast.makeText(context, "保存できませんでした：${it.message}", Toast.LENGTH_LONG).show() }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "アプリが落ちたとき（翻訳中・画面翻訳中を含む）の様子を、端末の中に残します。" +
                    "例外で落ちたときはその場で、ネイティブのライブラリで落ちた・応答なし・使っている最中にOSに止められた、" +
                    "のときは次にアプリを開いたときに記録します。どこにも送りません。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(
                "書き出すと、記録と、今のアプリのログを1つの zip にします。ログには、アプリの動き（訳した件数や時間、" +
                    "失敗など）が入り、訳した文や話した言葉は入りません。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(enabled = !exporting, onClick = { save.launch(CrashArchive.fileName(System.currentTimeMillis())) }) {
                    Text(if (exporting) "書き出し中…" else "書き出す")
                }
                OutlinedButton(enabled = entries.isNotEmpty() && !exporting, onClick = { confirm = true }) { Text("記録を消す") }
            }
        }
    }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("記録（${entries.size}件）", style = MaterialTheme.typography.titleMedium)
            if (entries.isEmpty()) {
                Text("まだありません", style = MaterialTheme.typography.bodySmall)
            }
            entries.forEachIndexed { i, entry ->
                if (i > 0) HorizontalDivider()
                Column {
                    Text(entry.title, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "${CrashReports.timeText(entry.time).take(19)}　${entry.files.joinToString("・")}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("記録を消しますか") },
            text = { Text("${entries.size}件の記録をすべて消します。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    journal.clear()
                }) { Text("消す") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

/** 設定のトップの「不具合の記録」の行に添える一言。 */
@Composable
fun diagnosticsSummary(): String {
    val context = LocalContext.current
    val entries by remember { Diagnostics.journal(context) }.entries.collectAsStateWithLifecycle()
    return if (entries.isEmpty()) "落ちたときの記録と書き出し" else "記録 ${entries.size}件、書き出し"
}
