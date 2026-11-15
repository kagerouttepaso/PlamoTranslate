package app.plamotranslate

import android.content.Context
import android.content.pm.ApplicationInfo
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.plamotranslate.log.Logs

/**
 * 詳細設定の控え（SharedPreferences の `advanced`）。ふつうは触らない調整と、不具合や遅さを調べるための記録の選択。
 * 正式版でも、設定の画面の下の「詳細設定を表示」で出せる（2026-11-15、作者の要望。調整の値をデバッグ版だけにせず、
 * 正式の署名の版で出先でも測れるようにする）。
 */
class AdvancedPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("advanced", Context.MODE_PRIVATE)
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    /** 設定のトップに「詳細設定」のページを出すか。 */
    var shown: Boolean
        get() = prefs.getBoolean("shown", false)
        set(value) = prefs.edit().putBoolean("shown", value).apply()

    /**
     * 詳しい記録を残すか：ログに利用者の文（話した言葉・原文・訳）を書き、会話翻訳で聞いた音声を WAV に書く。
     * 書き出した不具合の記録に入る。デバッグ版の既定はオン、正式版はオフ。
     */
    var detailedRecords: Boolean
        get() = prefs.getBoolean("detailedRecords", debuggable)
        set(value) = prefs.edit().putBoolean("detailedRecords", value).apply()
}

/** 設定のトップの「詳細設定」の行に添える一言。 */
@Composable
fun advancedSummary(): String {
    val context = LocalContext.current
    val detailed = remember { AdvancedPreferences(context).detailedRecords }
    return if (detailed) "音声認識の調整、詳しい記録を残している" else "音声認識の調整、詳しい記録"
}

/** 「詳細設定」のページ。 */
@Composable
fun AdvancedPage() {
    DetailedRecordsCard()
    RecognitionTuningCard()
}

/** 詳しい記録の選択。切り替えはその場で効く（ほかのプロセスの画面翻訳は、次に始めたときから）。 */
@Composable
private fun DetailedRecordsCard() {
    val context = LocalContext.current
    val preferences = remember { AdvancedPreferences(context) }
    var detailed by remember { mutableStateOf(preferences.detailedRecords) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("詳しい記録を残す", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Switch(checked = detailed, onCheckedChange = {
                    detailed = it
                    preferences.detailedRecords = it
                    Logs.allowUserText(it)
                })
            }
            Text(
                "遅さや誤りを調べるために、ログに話した言葉・読み取った文字・訳を書き、会話翻訳で聞いた音声を端末に残します。" +
                    "「不具合の記録」で書き出すと、これらが入ります。どこにも送りません。調べ終えたらオフにしてください。",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/**
 * 音声認識の調整。書き起こしの遅れを端末で切り分けるための値。次に会話翻訳・音声入力を開いたときに効く。
 * 仕事ごとの待ち時間と処理時間は、ログの「会話の認識: 認識 …」の行に出る。
 */
@Composable
private fun RecognitionTuningCard() {
    val context = LocalContext.current
    val preferences = remember { RecognitionPreferences(context) }
    // 既定に戻したときに選択の表示を読み直す
    var generation by remember { mutableIntStateOf(0) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("音声認識の調整", style = MaterialTheme.typography.titleMedium)
            Text(
                "書き起こしの遅さを調べるための値です。次に会話翻訳・音声入力を開いたときに効きます。",
                style = MaterialTheme.typography.bodySmall,
            )
            key(generation) {
                TuningChips("書き起こしのスレッド数", listOf(0, 1, 2, 4, 6, 8), preferences.transcribeThreads, { if (it == 0) "既定" else "$it" }) {
                    preferences.transcribeThreads = it
                }
                TuningChips("言語の識別のスレッド数", listOf(1, 2, 4), preferences.identifyThreads, { "$it" }) { preferences.identifyThreads = it }
                TuningChips("カードの言語を決める長さ", listOf(0.8f, 1.0f, 1.5f, 2.0f), preferences.cardLanguageSeconds, { "${it}秒" }) {
                    preferences.cardLanguageSeconds = it
                }
                TuningChips("話し声とみなすしきい値（VAD）", listOf(0.3f, 0.4f, 0.5f), preferences.vadThreshold, { "$it" }) {
                    preferences.vadThreshold = it
                }
                TuningChips("話し始めとみなす最短の長さ（VAD）", listOf(0.1f, 0.25f), preferences.vadMinSpeechSeconds, { "${it}秒" }) {
                    preferences.vadMinSpeechSeconds = it
                }
                TuningChips("会話中の翻訳のスレッド数", listOf(0, 1, 2, 4, 8), preferences.translateThreads, { if (it == 0) "変えない" else "$it" }) {
                    preferences.translateThreads = it
                }
            }
            OutlinedButton(onClick = {
                preferences.resetTuning()
                generation++
            }) { Text("既定に戻す") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> TuningChips(label: String, options: List<T>, initial: T, text: (T) -> String, save: (T) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    Text(label, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { option ->
            FilterChip(
                selected = selected == option,
                onClick = {
                    selected = option
                    save(option)
                },
                label = { Text(text(option)) },
            )
        }
    }
}
