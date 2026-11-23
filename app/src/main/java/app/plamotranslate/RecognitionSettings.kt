package app.plamotranslate

import android.content.Context
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
import app.plamotranslate.pipeline.diagnostics.InferenceRole
import app.plamotranslate.pipeline.speech.TranscriptionChoice
import app.plamotranslate.pipeline.speech.TranscriptionPreference
import app.plamotranslate.pipeline.speech.TranscriptionTuner
import androidx.compose.material3.TextButton
import androidx.compose.runtime.produceState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import app.plamotranslate.models.BundleDownloadControl
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelDownloads
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.pipeline.speech.SegmentationProfile
import app.plamotranslate.pipeline.speech.SpeechModel

/**
 * 音声認識モデルの設定（2026-11-15、作者の要望「音声入力のモデルを、翻訳モデルのように事前の設定で切り替えたい」）。
 * 会話翻訳とテキスト翻訳の音声入力が、ここで選んだモデルで聞く。会話翻訳で選べる言語は、選んだモデルの言語になる。
 */
class RecognitionPreferences(context: Context) : TranscriptionTuner.Store {
    private val prefs = context.getSharedPreferences("recognition", Context.MODE_PRIVATE)

    var transcriptionPreference: TranscriptionPreference
        get() = TranscriptionPreference.entries.firstOrNull { it.name == prefs.getString("asr.preference", null) } ?: TranscriptionPreference.AUTO
        set(value) = prefs.edit().putString("asr.preference", value.name).apply()

    fun clearTrials() {
        val editor = prefs.edit().remove("asr.lastTarget")
        prefs.all.keys.filter { it.startsWith("asr.trial.") }.forEach(editor::remove)
        editor.commit()
    }

    var lastTarget: String
        get() = prefs.getString("asr.lastTarget", null) ?: "未準備"
        set(value) = prefs.edit().putString("asr.lastTarget", value).apply()

    override fun read(key: String) = TranscriptionChoice.decode(prefs.getString("asr.trial.$key", null))
    override fun write(key: String, choice: TranscriptionChoice) {
        check(prefs.edit().putString("asr.trial.$key", choice.encode()).commit()) { "試走結果を保存できませんでした" }
    }

    var model: SpeechModel
        get() = prefs.getString("model", null)?.let { name -> SpeechModel.entries.firstOrNull { it.name == name } }
            ?: SpeechModel.DEFAULT
        set(value) = prefs.edit().putString("model", value.name).apply()

    // ---- 調整（設定の詳細設定で変える。docs/設計/会話翻訳.md「認識の段と CPU の割り当て」） ----

    /** 書き起こしのスレッド数（0はモデルの既定）。 */
    var transcribeThreads: Int
        get() = prefs.getInt("transcribeThreads", 0)
        set(value) = prefs.edit().putInt("transcribeThreads", value).apply()

    var identifyThreads: Int
        get() = prefs.getInt("identifyThreads", AsrTuning.DEFAULT_IDENTIFY_THREADS)
        set(value) = prefs.edit().putInt("identifyThreads", value).apply()

    /** カードの言語を決める長さ（秒）。 */
    var cardLanguageSeconds: Float
        get() = prefs.getFloat("cardLanguageSeconds", DEFAULT_PROFILE.cardLanguageSeconds)
        set(value) = prefs.edit().putFloat("cardLanguageSeconds", value).apply()

    var vadThreshold: Float
        get() = prefs.getFloat("vadThreshold", DEFAULT_TUNING.vadThreshold)
        set(value) = prefs.edit().putFloat("vadThreshold", value).apply()

    var vadMinSpeechSeconds: Float
        get() = prefs.getFloat("vadMinSpeechSeconds", DEFAULT_TUNING.vadMinSpeechSeconds)
        set(value) = prefs.edit().putFloat("vadMinSpeechSeconds", value).apply()

    /** 会話翻訳で聞いている間の、翻訳（llama.cpp）の CPU のスレッド数。0なら変えない。 */
    var translateThreads: Int
        get() = prefs.getInt("translateThreads", 0)
        set(value) = prefs.edit().putInt("translateThreads", value).apply()

    val asrTuning: AsrTuning
        get() = AsrTuning(transcribeThreads, identifyThreads, vadThreshold, vadMinSpeechSeconds)

    /** 調整の値を既定に戻す（モデルの選択は残す）。 */
    fun resetTuning() {
        prefs.edit().apply { TUNING_KEYS.forEach(::remove) }.apply()
    }

    /** 用途の区切り方に、調整の値を入れたもの。 */
    fun profile(base: SegmentationProfile): SegmentationProfile = base.copy(cardLanguageSeconds = cardLanguageSeconds)

    private companion object {
        val DEFAULT_PROFILE = SegmentationProfile.CONVERSATION
        val DEFAULT_TUNING = AsrTuning()
        val TUNING_KEYS = listOf(
            "transcribeThreads", "identifyThreads", "cardLanguageSeconds", "vadThreshold", "vadMinSpeechSeconds", "translateThreads",
        )
    }
}

@Composable
fun recognitionSummary(): String {
    val context = LocalContext.current
    return remember { RecognitionPreferences(context).model.label }
}

/**
 * 音声認識モデルの選択。選んだモデルが端末に無ければ、ここからダウンロードする（初回の準備で選ばなかったモデル）。
 * 切り替えは、モデルがそろうまで効かない（会話翻訳は、モデルが無いと伝えて止まる）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RecognitionCard() {
    val context = LocalContext.current
    val preferences = remember { RecognitionPreferences(context) }
    var model by remember { mutableStateOf(preferences.model) }
    var preference by remember { mutableStateOf(preferences.transcriptionPreference) }
    val fallback = remember { GpuGuard.disabledNote(context, InferenceRole.Transcription) }
    val download by ModelDownloads.items.collectAsStateWithLifecycle()

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
                val bundle = ModelBundle.of(m)
                val missing by produceState(bundle.missing(context), m, download) {
                    value = withContext(Dispatchers.IO) { bundle.missing(context, verify = true) }
                }
                Text("${m.label}：${m.note}", style = MaterialTheme.typography.bodySmall)
                Text(
                    "聞ける言語：" + m.languages.joinToString("・") { it.label },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (missing.isNotEmpty()) {
                    BundleDownloadControl(bundle, if (m == SpeechModel.QWEN3_ASR) "Qwen3-ASR のモデルを取得・更新します" else "${m.label}は使う前にダウンロードします") { ModelDownloads.enqueue(context, listOf(bundle)) }
                }
            }
            if (model == SpeechModel.QWEN3_ASR) {
                Text("書き起こしの実行先")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for ((value, label) in listOf(TranscriptionPreference.AUTO to "自動", TranscriptionPreference.CPU to "CPU", TranscriptionPreference.GPU to "GPU")) {
                        FilterChip(selected = preference == value, onClick = {
                            preferences.transcriptionPreference = value
                            preference = value
                        }, label = { Text(label) })
                    }
                }
                Text("前回の準備：${preferences.lastTarget}", style = MaterialTheme.typography.bodySmall)
                if (fallback != null) {
                    Text("書き起こしは CPU で動きます：$fallback")
                    TextButton(onClick = {
                        GpuGuard.retry(context, InferenceRole.Transcription)
                        preferences.clearTrials()
                        restartApp(context)
                    }) { Text("書き起こしで GPU をもう一度試す（起動し直す）") }
                }
            }
        }
    }
}
