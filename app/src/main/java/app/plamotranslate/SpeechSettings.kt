package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.llm.Language
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.tts.SupertonicSpeaker
import kotlin.math.roundToInt

/*
 * 読み上げの設定（2026-11-08、作者の要望「読み上げもモデルを切り替えできたら嬉しい」）。
 * 読むもの（Supertonic 3・端末の読み上げ）、Supertonic 3のダウンロード、声と速さ、試しに聞くボタン。
 */

private val SPEEDS = listOf(0.8f, 1f, 1.2f, 1.4f)
private const val SAMPLE_KEY = "settings-sample"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SpeechCard() {
    val context = LocalContext.current
    val download by ModelDownloads.state.collectAsStateWithLifecycle()
    val failure by ModelDownloads.failure.collectAsStateWithLifecycle()
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val preferences = remember { SpeechPreferences(context) }
    var engine by remember { mutableStateOf(preferences.engine) }
    var voice by remember { mutableStateOf(preferences.voice) }
    var speed by remember { mutableStateOf(preferences.speed) }
    var steps by remember { mutableStateOf(preferences.steps) }
    var refresh by remember { mutableStateOf(0) }
    var askConsent by remember { mutableStateOf(false) }
    var agreed by remember { mutableStateOf(preferences.supertonicAgreed) }
    val present = remember(refresh, download) { ModelCatalog.speechPresent(context) }
    // 声の数はモデルを読み込むと分かる。Supertonic 3を選んでいてモデルがあれば、開いたときに読み込む
    var voices by remember { mutableStateOf(0) }
    LaunchedEffect(present, engine) {
        if (present && engine == SpeechEngine.SUPERTONIC) {
            voices = runCatching { ReadAloud.supertonicVoices(context) }.getOrDefault(0)
        }
    }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "会話翻訳のカードの訳文をタップしたとき、テキスト翻訳の🔊を押したときに読みます。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SpeechEngine.entries.forEach { e ->
                    FilterChip(
                        selected = engine == e,
                        onClick = {
                            engine = e
                            preferences.engine = e
                        },
                        label = { Text(e.label) },
                    )
                }
            }
            SpeechEngine.entries.forEach {
                Text("${it.label}：${it.note}", style = MaterialTheme.typography.bodySmall)
            }

            if (engine == SpeechEngine.SUPERTONIC) {
                val files = ModelBundle.SPEECH.files
                val state = download
                if (state is DownloadState.Running && state.bundle == ModelBundle.SPEECH) {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はアプリを開いたままにしてください",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                } else if (!present) {
                    Text(
                        "Supertonic 3は使う前に約${files.sumOf { it.size } / 1_000_000}MBをダウンロードします。" +
                            "ダウンロードするまでは端末の読み上げで読みます。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    // 一度に取るのは1組だけ。2Bを取っている間は押せなくし、理由を出す
                    val busy = state is DownloadState.Running
                    OutlinedButton(enabled = !busy, onClick = { askConsent = true }) { Text("Supertonic 3をダウンロード") }
                    if (busy) Text("ほかのモデルのダウンロードが終わると押せます", style = MaterialTheme.typography.bodySmall)
                }
                // 前の版でモデルを取った人は、同意するまで端末の読み上げで読む
                if (present && !agreed) {
                    Text(
                        "Supertonic 3 を使うには、利用条件への同意が要ります。同意するまでは端末の読み上げで読みます。",
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = { askConsent = true }) { Text("利用条件を読んで同意する") }
                }
                if (askConsent) {
                    SupertonicConsentDialog(
                        downloading = !present,
                        onAgree = {
                            askConsent = false
                            preferences.supertonicAgreed = true
                            agreed = true
                            if (!present) ModelDownloads.start(context, ModelBundle.SPEECH, onCompleted = { refresh++ })
                        },
                        onDismiss = { askConsent = false },
                    )
                }
                failure?.takeIf { it.bundle == ModelBundle.SPEECH }?.let {
                    Text(it.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (present) {
                    Text("音質（段数 $steps）", style = MaterialTheme.typography.titleSmall)
                    Slider(
                        value = steps.toFloat(),
                        onValueChange = { steps = it.roundToInt() },
                        onValueChangeFinished = { preferences.steps = steps },
                        valueRange = SupertonicSpeaker.STEPS_RANGE.first.toFloat()..SupertonicSpeaker.STEPS_RANGE.last.toFloat(),
                        steps = SupertonicSpeaker.STEPS_RANGE.last - SupertonicSpeaker.STEPS_RANGE.first - 1,
                    )
                    Text(
                        "増やすと話している最中のザラつきが減り、読み始めるまでが長くなります（既定 ${SupertonicSpeaker.DEFAULT_STEPS}）。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                if (present && voices > 0) {
                    Text("声", style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        (0 until voices).forEach { v ->
                            FilterChip(
                                selected = voice == v,
                                onClick = {
                                    voice = v
                                    preferences.voice = v
                                },
                                label = { Text("${v + 1}") },
                            )
                        }
                    }
                }
            }

            Text("速さ", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SPEEDS.forEach { s ->
                    FilterChip(
                        selected = speed == s,
                        onClick = {
                            speed = s
                            preferences.speed = s
                        },
                        label = { Text("×$s") },
                    )
                }
            }
            if (engine == SpeechEngine.SYSTEM) {
                Text("端末の読み上げの速さと声は、Androidの設定の「テキスト読み上げの出力」で変えます。", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Language.JA to "こんにちは。駅までの行き方を教えてください。",
                    Language.KO to "안녕하세요. 역까지 가는 방법을 알려 주세요.",
                    Language.EN to "Hello. Could you tell me how to get to the station?",
                ).forEach { (lang, text) ->
                    val key = "$SAMPLE_KEY-${lang.code}"
                    OutlinedButton(onClick = { ReadAloud.toggle(context, key, text, lang) }) {
                        Text(if (speaking == key) "■ 止める" else "▶ ${lang.label}")
                    }
                }
            }
        }
    }
}

/**
 * Supertonic 3 をダウンロードする前の同意（docs/判断/2026-11-13_ライセンスの表示を同梱した全文と明記にする.md）。
 * BigScience Open RAIL-M License 第5項は、アプリの作者に、使う人へ用途の制限（Attachment A）を守らせるよう求める。
 */
@Composable
private fun SupertonicConsentDialog(downloading: Boolean, onAgree: () -> Unit, onDismiss: () -> Unit) {
    var fullText by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Supertonic 3 の利用条件") },
        text = {
            // 項目が多く、横向きでは収まらないので、中身をスクロールできるようにする
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    "Supertonic 3（Supertone Inc.）は BigScience Open RAIL-M License のもとで使えます。" +
                        "使う前に、次の使い方をしないことに同意してください（ライセンスの Attachment A の要旨。正本は全文）。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    "・法令に反すること\n" +
                        "・未成年を搾取し、害すること\n" +
                        "・人を害する目的で偽りの情報を作り、広めること\n" +
                        "・人を害し得る個人を特定する情報を作り、広めること\n" +
                        "・機械が作ったと明示せずに、作った音声などを広めること\n" +
                        "・他人を中傷し、嫌がらせをすること\n" +
                        "・同意なく他人になりすますこと\n" +
                        "・人の法的な権利に不利な決定を、完全に自動で行うこと\n" +
                        "・社会的な振る舞いや属性、法で守られた特徴による差別や害\n" +
                        "・年齢や障害などの弱みにつけこみ、害を与えるように行動をゆがめること\n" +
                        "・医療の助言や検査結果の解釈\n" +
                        "・司法・法執行・入国審査などで、個人を予測し判断するための情報を作ること",
                    style = MaterialTheme.typography.bodySmall,
                )
                TextButton(onClick = { fullText = true }) { Text("ライセンスの全文") }
            }
        },
        confirmButton = { TextButton(onClick = onAgree) { Text(if (downloading) "同意してダウンロード" else "同意する") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("やめる") } },
    )
    if (fullText) LicenseTextDialog(LicenseText.OPENRAIL) { fullText = false }
}
