package app.plamotranslate.models

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/** 待ち行列の組を、足した順に上から並べる。取っている組は進み具合、待っている組は「待機中」を出す。 */
@Composable
fun DownloadQueueList(items: List<DownloadItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { DownloadRow(it) }
    }
}

@Composable
private fun DownloadRow(item: DownloadItem) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${item.bundle.label}（約${item.bundle.size / 1_000_000}MB）", style = MaterialTheme.typography.bodyMedium)
        when (val status = item.status) {
            DownloadStatus.Waiting -> {
                LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                Text("待機中", style = MaterialTheme.typography.labelSmall)
            }
            is DownloadStatus.Running -> {
                LinearProgressIndicator(progress = { status.progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${status.done / 1_000_000} / ${status.total / 1_000_000} MB　${status.current.label}",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            DownloadStatus.Done -> {
                LinearProgressIndicator(progress = { 1f }, modifier = Modifier.fillMaxWidth())
                Text("完了", style = MaterialTheme.typography.labelSmall)
            }
            is DownloadStatus.Failed ->
                Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
    }
}

/**
 * 設定の画面で、選んだモデルの組（[bundle]）が端末に無いときに出す欄。取っている・待っている間は進み具合と中止のボタン、
 * 無ければ説明（[note]）とダウンロードのボタン（押すと [onDownload]）、失敗したらその理由を出す。置き済みなら何も出さない。
 */
@Composable
fun BundleDownloadControl(bundle: ModelBundle, note: String, onDownload: () -> Unit) {
    val context = LocalContext.current
    val items by ModelDownloads.items.collectAsStateWithLifecycle()
    val status = items.lastOrNull { it.bundle == bundle }?.status
    val missing = bundle.missing(context)
    when {
        status is DownloadStatus.Waiting || status is DownloadStatus.Running -> {
            DownloadQueueList(listOf(DownloadItem(bundle, status)))
            Text("ダウンロード中はアプリを開いたままにしてください", style = MaterialTheme.typography.labelSmall)
            OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
        }
        missing.isNotEmpty() -> {
            Text("$note（約${(missing.sumOf { it.size } + 1_048_575) / 1_048_576} MiB。Wi-Fiでの実行をおすすめします）", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = onDownload) { Text("${bundle.title}をダウンロード") }
            if (status is DownloadStatus.Failed) {
                Text(status.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
