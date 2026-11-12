package app.plamotranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.material3.Switch
import app.plamotranslate.overlay.TranslationCache
import app.plamotranslate.screen.OverlayAccessibilityService
import app.plamotranslate.screen.ScreenPreferences
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.TranslationModel

/** 設定（2026-11-06、トップ画面から分けた）。翻訳モデルの選択・2Bのダウンロード・今の状態・読み上げ・パフォーマンス・このアプリについて（2026-11-08）。 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { SettingsScreen(onBack = ::finish) } }
    }

    override fun onStop() {
        super.onStop()
        // 試し聞きを、画面を離れても鳴らし続けない
        ReadAloud.stop()
    }
}

@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack) { Text("←", fontSize = 22.sp) }
                Text("設定", style = MaterialTheme.typography.titleLarge)
            }
            ModelChooser(model)
            ModelStatus(model, onRetry = { EngineHolder.ensureLoaded(context.applicationContext) })
            SpeechCard()
            TranslationCacheCard()
            OpaqueOverlayCard()
            PerformanceCard(model)
            val version = remember {
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
            }
            AboutCard(version)
        }
    }
}

/**
 * 翻訳モデルの選択（2026-11-06、作者の要望「2Bも選べるようにして、翻訳品質と速度の違いをチェックしたい」）。
 * 2Bが端末に無ければ、ここからダウンロードする。
 */
@Composable
private fun ModelChooser(model: ModelState) {
    val context = LocalContext.current
    val download by ModelDownloads.state.collectAsStateWithLifecycle()
    val failure by ModelDownloads.failure.collectAsStateWithLifecycle()
    val current = (model as? ModelState.Ready)?.model ?: EngineHolder.selected(context)
    var refresh by remember { mutableStateOf(0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("翻訳モデル", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TranslationModel.entries.forEach { m ->
                    val present = remember(refresh, download) { m.isPresent(context) }
                    FilterChip(
                        selected = current == m,
                        enabled = present && model !is ModelState.Loading,
                        onClick = { EngineHolder.select(context, m) },
                        label = { Text(m.label) },
                    )
                }
            }
            TranslationModel.entries.forEach {
                Text("${it.label}：${it.note}", style = MaterialTheme.typography.bodySmall)
            }
            val large = TranslationModel.LARGE
            val largePresent = remember(refresh, download) { large.isPresent(context) }
            val state = download
            if (state is DownloadState.Running && state.bundle == ModelBundle.LARGE_TRANSLATION) {
                val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                Text(
                    "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                    style = MaterialTheme.typography.labelSmall,
                )
                OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
            } else if (!largePresent) {
                Text(
                    "2Bは使う前に約${large.file.size / 1_000_000}MBをダウンロードします（Wi-Fiでの実行をおすすめします）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                // 一度に取るのは1組だけ。読み上げのモデルを取っている間は押せなくし、理由を出す
                val busy = state is DownloadState.Running
                OutlinedButton(enabled = !busy, onClick = {
                    ModelDownloads.start(context, ModelBundle.LARGE_TRANSLATION, onCompleted = { refresh++ })
                }) { Text("2Bをダウンロード") }
                if (busy) Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
            }
            failure?.takeIf { it.bundle == ModelBundle.LARGE_TRANSLATION }?.let {
                Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/**
 * 画面翻訳の訳の控え（2026-11-12、作者の要望「設定から削除もできるように」）。件数を出し、確かめてから全部消す。
 * 控えは端末のデータベースにあり（TranslationCache）、画面翻訳を終えても次に使う。誤訳が残ったときや場所を空けたいときに消す。
 */
@Composable
private fun TranslationCacheCard() {
    val context = LocalContext.current
    val cache = remember { TranslationCache.get(context) }
    val entries by cache.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cache.refreshCount() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("画面翻訳の訳の控え", style = MaterialTheme.typography.titleMedium)
            Text(
                "一度訳した文を端末に残し、同じ文はすぐ出します。2Bの訳があれば0.8Bでもそれを使い、" +
                    "2Bで訳すと0.8Bの訳を上書きします。${TranslationCache.MAX_ENTRIES}件を超えると古いものから消します。",
                style = MaterialTheme.typography.bodySmall,
            )
            Text(if (entries < 0) "数えています…" else "${entries}件", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(enabled = entries > 0, onClick = { confirm = true }) { Text("訳の控えを消す") }
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("訳の控えを消しますか") },
            text = { Text("${entries}件の訳をすべて消します。次からは訳し直すので、そのぶん時間がかかります。") },
            confirmButton = {
                TextButton(onClick = {
                    confirm = false
                    scope.launch { cache.clear() }
                }) { Text("消す") }
            },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text("やめる") } },
        )
    }
}

/**
 * 画面翻訳の訳の枠を透かさない（2026-11-12、作者の採用）。ユーザー補助のサービス（OverlayAccessibilityService）を使うと、
 * 訳の枠を不透明のまま、触れた操作を下のアプリへ通せる。使う人がユーザー補助を有効にする手順を、ここで案内する。
 */
@Composable
private fun OpaqueOverlayCard() {
    val context = LocalContext.current
    val preferences = remember { ScreenPreferences(context) }
    var on by remember { mutableStateOf(preferences.opaqueOverlay) }
    val connected by OverlayAccessibilityService.connected.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("訳の枠を透かさない", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = on, onCheckedChange = {
                    on = it
                    preferences.opaqueOverlay = it
                })
            }
            Text(
                "画面翻訳の訳の下から元の文字が透けないようにします。訳の上に触れた操作は、今までどおり下のアプリへ届きます。" +
                    "アプリの切り替えや戻る・ホームの操作で、訳している途中の処理も確実に取り消せます。" +
                    "Androidのユーザー補助の機能を使います。画面の文字や内容は読み取らず、どこにも送りません。",
                style = MaterialTheme.typography.bodySmall,
            )
            if (on) {
                if (connected) {
                    Text("ユーザー補助：有効（次に訳したときから透けなくなります）", style = MaterialTheme.typography.bodyMedium)
                } else if (OverlayAccessibilityService.isEnabled(context)) {
                    // 有効なのにつながっていない：端末に止められて切り離された（REDMAGICのゲーム支援で確かめた。docs/資料/端末の能力.md）
                    Text(
                        "ユーザー補助は有効ですが、端末に止められて切れています（ゲームを開いたときなどに起きます）。" +
                            "ユーザー補助の設定で「PLaMo翻訳（画面翻訳の訳の枠）」を一度オフにしてからオンにしてください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    }) { Text("ユーザー補助の設定を開く") }
                } else {
                    Text(
                        "ユーザー補助がまだ有効ではありません。有効にするまでは今までどおり少し透けます。次の手順で有効にしてください。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "1. 「アプリ情報を開く」→ 右上の︙ →「制限付き設定を許可」（表示されない端末では飛ばす）\n" +
                            "2. 「ユーザー補助の設定を開く」→ ダウンロードしたアプリ（またはインストール済みのアプリ）→ " +
                            "「PLaMo翻訳（画面翻訳の訳の枠）」をオン\n" +
                            "3. 「デバイスのフル コントロール」の確認が出たら「許可」",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
                            )
                        }) { Text("アプリ情報を開く") }
                        OutlinedButton(onClick = {
                            context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        }) { Text("ユーザー補助の設定を開く") }
                    }
                }
            } else if (connected) {
                Text(
                    "ユーザー補助は有効のままです。使わないなら、ユーザー補助の設定でオフにできます。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ModelStatus(model: ModelState, onRetry: () -> Unit) {
    when (model) {
        ModelState.Loading -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.padding(end = 8.dp))
            Text("モデルを読み込み中…")
        }
        is ModelState.Ready -> Text(
            "今の翻訳モデル：${model.model.label}（${if (model.engine.usesGpu) "GPU" else "CPU"}で動作、" +
                "読込 ${"%.1f".format(model.loadMillis / 1000.0)}秒）",
            style = MaterialTheme.typography.bodyMedium,
        )
        is ModelState.Missing -> Text("翻訳モデルが端末にありません", color = MaterialTheme.colorScheme.error)
        is ModelState.Failed -> Column {
            Text(model.message, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onRetry) { Text("読み直す") }
        }
    }
}
