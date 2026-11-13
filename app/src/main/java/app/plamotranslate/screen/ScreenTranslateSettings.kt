package app.plamotranslate.screen

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.R
import app.plamotranslate.translation.SqlitePersistentCache
import kotlinx.coroutines.launch

/** 設定の「画面翻訳」のページ（docs/設計/アーキテクチャ.md「設定の画面」）。 */
@Composable
fun ScreenTranslateSettings() {
    OpaqueOverlayCard()
    TranslationCacheCard()
}

/** 設定のトップの「画面翻訳」の行に添える一言。 */
@Composable
fun screenTranslateSummary(): String {
    val context = LocalContext.current
    val opaque = remember { ScreenPreferences(context).opaqueOverlay }
    return "訳の枠を透かさない：${if (opaque) "オン" else "オフ"}、訳の控え"
}

/**
 * 画面翻訳の訳の控え（2026-11-12、作者の要望「設定から削除もできるように」）。件数を出し、確かめてから全部消す。
 * 控えは端末のデータベースにあり（SqlitePersistentCache）、画面翻訳を終えても次に使う。誤訳が残ったときや場所を空けたいときに消す。
 */
@Composable
private fun TranslationCacheCard() {
    val context = LocalContext.current
    val cache = remember { SqlitePersistentCache.get(context) }
    val entries by cache.entries.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { cache.refreshCount() }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("訳の控え", style = MaterialTheme.typography.titleMedium)
            Text(
                "一度訳した文を端末に残し、同じ文はすぐ出します。2Bの訳があれば0.8Bでもそれを使い、" +
                    "2Bで訳すと0.8Bの訳を上書きします。${SqlitePersistentCache.MAX_ENTRIES}件を超えると古いものから消します。",
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
                            "ユーザー補助の設定で「${stringResource(R.string.a11y_overlay_label)}」を一度オフにしてからオンにしてください。",
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
                            "「${stringResource(R.string.a11y_overlay_label)}」をオン\n" +
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
