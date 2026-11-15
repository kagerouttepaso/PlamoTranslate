package app.plamotranslate

import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import app.plamotranslate.asr.AsrTuning
import app.plamotranslate.asr.SpeechEngines
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechModel

/**
 * 音声認識モデルの設定（2026-11-15、作者の要望「音声入力のモデルを、翻訳モデルのように事前の設定で切り替えたい」）。
 * 会話翻訳とテキスト翻訳の音声入力が、ここで選んだモデルで聞く。会話翻訳で選べる言語は、選んだモデルの言語になる。
 */
class RecognitionPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)

    /** デバッグ版だけ、調整の値（[asrTuning]・[cardLanguageSeconds]・[translateThreads]）を読む。正式版は既定の値。 */
    private val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0

    var model: SpeechModel
        get() = prefs.getString("model", null)?.let { name -> SpeechModel.entries.firstOrNull { it.name == name } }
            ?: SpeechModel.DEFAULT
        set(value) = prefs.edit().putString("model", value.name).apply()

    // ---- 調整（デバッグ版。docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md「計測」） ----

    /** 書き起こしのスレッド数（0はモデルの既定）。 */
    var transcribeThreads: Int
        get() = if (debuggable) prefs.getInt("transcribeThreads", 0) else 0
        set(value) = prefs.edit().putInt("transcribeThreads", value).apply()

    var identifyThreads: Int
        get() = if (debuggable) prefs.getInt("identifyThreads", AsrTuning.DEFAULT_IDENTIFY_THREADS) else AsrTuning.DEFAULT_IDENTIFY_THREADS
        set(value) = prefs.edit().putInt("identifyThreads", value).apply()

    /** カードの言語を決める長さ（秒）。 */
    var cardLanguageSeconds: Float
        get() = if (debuggable) prefs.getFloat("cardLanguageSeconds", DEFAULT_PROFILE.cardLanguageSeconds) else DEFAULT_PROFILE.cardLanguageSeconds
        set(value) = prefs.edit().putFloat("cardLanguageSeconds", value).apply()

    var vadThreshold: Float
        get() = if (debuggable) prefs.getFloat("vadThreshold", DEFAULT_TUNING.vadThreshold) else DEFAULT_TUNING.vadThreshold
        set(value) = prefs.edit().putFloat("vadThreshold", value).apply()

    var vadMinSpeechSeconds: Float
        get() = if (debuggable) prefs.getFloat("vadMinSpeechSeconds", DEFAULT_TUNING.vadMinSpeechSeconds) else DEFAULT_TUNING.vadMinSpeechSeconds
        set(value) = prefs.edit().putFloat("vadMinSpeechSeconds", value).apply()

    /** 会話翻訳で聞いている間の、翻訳（llama.cpp）の CPU のスレッド数。0なら変えない。 */
    var translateThreads: Int
        get() = if (debuggable) prefs.getInt("translateThreads", 0) else 0
        set(value) = prefs.edit().putInt("translateThreads", value).apply()

    val asrTuning: AsrTuning
        get() = AsrTuning(transcribeThreads, identifyThreads, vadThreshold, vadMinSpeechSeconds)

    /** 用途の区切り方に、調整の値を入れたもの。 */
    fun profile(base: SegmentationProfile): SegmentationProfile = base.copy(cardLanguageSeconds = cardLanguageSeconds)

    private companion object {
        val DEFAULT_PROFILE = SegmentationProfile.CONVERSATION
        val DEFAULT_TUNING = AsrTuning()
    }
}

@Composable
fun recognitionSummary(): String {
    val context = LocalContext.current
    return remember { RecognitionPreferences(context).model.label }
}

/**
 * 技術検証の版：Qwen3-ASR と Whisper tiny は、まだ設定の画面から取れない（PCから adb で置く）。
 * 合意した形は、このモデルに切り替えたときにダウンロードを始める（読み上げの Supertonic 3 と同じ）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecognitionCard() {
    val context = LocalContext.current
    val preferences = remember { RecognitionPreferences(context) }
    var model by remember { mutableStateOf(preferences.model) }
    val asrDir = remember { ModelCatalog.asrDir(context) }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "会話翻訳とテキスト翻訳の音声入力で使います。会話翻訳で選べる言語は、選んだモデルが聞ける言語になります。" +
                    "切り替えは、次に会話翻訳・音声入力を開いたときに効きます。",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechModel.entries.forEach { m ->
                    FilterChip(
                        selected = model == m,
                        onClick = {
                            model = m
                            preferences.model = m
                        },
                        label = { Text(m.label) },
                    )
                }
            }
            SpeechModel.entries.forEach { m ->
                val missing = remember(m) { SpeechEngines.missingFiles(m, asrDir) }
                Text("${m.label}：${m.note}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "聞ける言語：" + m.languages.joinToString("・") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (missing.isNotEmpty()) {
                    Text(
                        "端末にモデルがありません（検証版はPCから置きます）",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) RecognitionTuningSection(preferences)
        }
    }
}

/**
 * 調整（デバッグ版だけ）。書き起こしの遅れを端末で切り分けるための値。次に会話翻訳・音声入力を開いたときに効く。
 * 仕事ごとの待ち時間と処理時間は、ログの「会話の認識: 認識 …」の行に出る。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RecognitionTuningSection(preferences: RecognitionPreferences) {
    Text("調整（デバッグ版）", style = MaterialTheme.typography.titleSmall)
    Text(
        "次に会話翻訳・音声入力を開いたときに効きます。仕事ごとの時間はログの「会話の認識: 認識」の行に出ます。",
        style = MaterialTheme.typography.bodySmall,
    )
    TuningChips("書き起こしのスレッド数", listOf(0, 2, 4, 6, 8), preferences.transcribeThreads, { if (it == 0) "既定" else "$it" }) {
        preferences.transcribeThreads = it
    }
    TuningChips("言語の識別のスレッド数", listOf(1, 2, 4), preferences.identifyThreads, { "$it" }) { preferences.identifyThreads = it }
    TuningChips("カードの言語を決める長さ", listOf(0.8f, 1.0f, 1.5f, 2.0f), preferences.cardLanguageSeconds, { "${it}秒" }) {
        preferences.cardLanguageSeconds = it
    }
    TuningChips("VAD のしきい値", listOf(0.3f, 0.4f, 0.5f), preferences.vadThreshold, { "$it" }) { preferences.vadThreshold = it }
    TuningChips("VAD の話し始めの最短", listOf(0.1f, 0.25f), preferences.vadMinSpeechSeconds, { "${it}秒" }) {
        preferences.vadMinSpeechSeconds = it
    }
    TuningChips("会話中の翻訳のスレッド数", listOf(0, 1, 2, 4), preferences.translateThreads, { if (it == 0) "変えない" else "$it" }) {
        preferences.translateThreads = it
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
