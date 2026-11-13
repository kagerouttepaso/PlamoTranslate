package app.plamotranslate

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.plamotranslate.update.UpdateSection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/*
 * 配布のための表示（docs/判断/2026-11-13_ライセンスの表示を同梱した全文と明記にする.md）。
 * - モデルはアプリに同梱せず、端末が配布元から取る。名前・作者・ライセンス名・元のモデルを明記し、全文かその置き場を示す。
 * - アプリに組み込んだソフトウェアは、ライセンス文の全文を assets/licenses/ に同梱し、ここから読めるようにする（MIT・BSD・Apache-2.0 の条件）。
 *   全文の作り方は docs/手順/開発環境.md「ライセンス文を集め直す」。
 * - PLaMo Community License 第4条の「Built with PLaMo」の明示と、契約の写し（条文へのリンク）。
 * - ML Kit の規約が求める、Google への利用の指標の送信の説明。
 */

const val BUILT_WITH_PLAMO = "Built with PLaMo"

const val PLAMO_LICENSE_URL = "https://plamo.preferredai.jp/info/plamo-community-license-ja"

/** プライバシーポリシー（リポジトリの PRIVACY.md）。 */
const val PRIVACY_URL = "https://github.com/kagerouttepaso/PlamoTranslate/blob/main/PRIVACY.md"

/** 同梱したライセンス文（assets/licenses/ の中のファイル名）。 */
enum class LicenseText(val title: String, val asset: String) {
    APACHE("Apache License 2.0（全文）", "apache-2.0.txt"),
    NATIVE("組み込んだネイティブのライブラリ", "native.txt"),
    MAVEN("Android のライブラリの一覧", "maven.txt"),
    GOOGLE("ML Kit・Google のライブラリが使う第三者のソフトウェア", "google-third-party.txt"),
    ONNXRUNTIME("ONNX Runtime が使う第三者のソフトウェア", "onnxruntime-third-party-notices.txt"),
    OPENRAIL("BigScience Open RAIL-M License（Supertonic 3）", "supertonic-openrail-m.txt"),
    FUNASR("FunASR Model Open Source License Agreement 1.1（SenseVoice）", "funasr-model-license.txt"),
}

/** 端末が配布元から取るモデル。 */
private data class ModelNotice(
    val name: String,
    val role: String,
    val author: String,
    val license: String,
    val url: String,
    val text: LicenseText? = null,
)

private val MODELS = listOf(
    ModelNotice(
        "PLaMo 3 Translate 0.8B・2B（GGUF）", "翻訳", "Preferred Networks",
        "PLaMo Community License", PLAMO_LICENSE_URL,
    ),
    ModelNotice(
        "SenseVoice Small（sherpa-onnx 用の int8 版）", "音声認識", "Alibaba Group（FunAudioLLM）",
        "FunASR Model Open Source License Agreement 1.1", "https://huggingface.co/FunAudioLLM/SenseVoiceSmall", LicenseText.FUNASR,
    ),
    ModelNotice(
        "Silero VAD", "話しているかの判定", "Silero Team（Copyright (c) 2020-present Silero Team）",
        "MIT License", "https://github.com/snakers4/silero-vad/blob/master/LICENSE",
    ),
    ModelNotice(
        "Supertonic 3（sherpa-onnx 用の int8 版）", "読み上げ（選んだときだけ）", "Supertone Inc.（Copyright (c) 2026 Supertone Inc.）",
        "BigScience Open RAIL-M License", "https://huggingface.co/Supertone/supertonic-3", LicenseText.OPENRAIL,
    ),
    ModelNotice(
        "PP-OCRv6 manga", "漫画の文字の検出と認識", "Kellenok（元は PaddlePaddle の PaddleOCR。Manga109-s で学習）",
        "Apache License 2.0", "https://huggingface.co/Kellenok/PP-OCRv6_manga", LicenseText.APACHE,
    ),
    ModelNotice(
        "PP-OCRv5 korean（korean_PP-OCRv5_mobile_rec の ONNX 版）", "韓国語の文字の認識",
        "PaddlePaddle（PaddleOCR）。ONNX への変換は Wycliffe Associates（DocScanner）",
        "Apache License 2.0", "https://huggingface.co/PaddlePaddle/korean_PP-OCRv5_mobile_rec", LicenseText.APACHE,
    ),
)

/** アプリに組み込んだソフトウェア。全文は [text]（同梱）にある。 */
private data class SoftwareNotice(val name: String, val license: String, val text: LicenseText)

private val SOFTWARE = listOf(
    SoftwareNotice("llama.cpp・ggml（翻訳モデルの実行）", "MIT License", LicenseText.NATIVE),
    SoftwareNotice("Arm KleidiAI（計算の高速化）", "Apache License 2.0", LicenseText.NATIVE),
    SoftwareNotice("ONNX Runtime（文字認識の実行）", "MIT License", LicenseText.NATIVE),
    SoftwareNotice("sherpa-onnx（音声認識・読み上げの実行）と、組み込まれた第三者のコード", "Apache License 2.0、MIT License、BSD License ほか", LicenseText.NATIVE),
    SoftwareNotice("LLVM libc++", "Apache License 2.0 with LLVM Exceptions", LicenseText.NATIVE),
    SoftwareNotice("ML Kit Text Recognition（文字の認識、Google）", "ML Kit Terms of Service", LicenseText.GOOGLE),
    SoftwareNotice("Android Jetpack・Compose・CameraX・Kotlin・kotlinx.coroutines", "Apache License 2.0", LicenseText.MAVEN),
)

fun openUrl(context: Context, url: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
}

/** トップ画面などの下に小さく出す表示。 */
@Composable
fun BuiltWithPlamoLabel(modifier: Modifier = Modifier) {
    Text(
        BUILT_WITH_PLAMO,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/**
 * 初回のモデルの準備の画面に出す、ライセンスへの案内。モデルを使うとライセンスに同意したことになる（契約の前文）。
 * 契約は18歳以上か、法定代理人の同意を求める（第2条）。
 */
@Composable
fun LicenseAgreementNote() {
    val context = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            "翻訳モデルPLaMo 3 Translateは、PLaMo Community Licenseのもとで使えます。" +
                "ダウンロードして使うと、このライセンスに同意したことになります。" +
                "18歳未満の方は、保護者の同意を得てから使ってください。個人の利用と非商用の利用は無料です。",
            style = MaterialTheme.typography.bodySmall,
        )
        TextButton(onClick = { openUrl(context, PLAMO_LICENSE_URL) }) { Text("ライセンスを読む") }
        BuiltWithPlamoLabel()
    }
}

/** 設定の画面の「このアプリについて」。版、非公式であること、通信とプライバシー、使っているモデルとソフトウェアのライセンス。 */
@Composable
fun AboutCard(version: String) {
    val context = LocalContext.current
    var shown by remember { mutableStateOf<LicenseText?>(null) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("このアプリについて", style = MaterialTheme.typography.titleMedium)
            Text("${stringResource(R.string.app_name)} $version", style = MaterialTheme.typography.bodyMedium)
            UpdateSection()
            Text(BUILT_WITH_PLAMO, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
            Text(
                "個人が作った非公式のアプリです。Preferred Networks とは関係ありません。PLaMo™ は Preferred Networks の商標です。" +
                    "訳は機械翻訳で、誤りを含むことがあります。大事な判断には使わないでください。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("通信とプライバシー", style = MaterialTheme.typography.titleSmall)
            Text(
                "翻訳・音声認識・文字の認識・読み上げは、すべて端末の中で動きます。訳す文字・画像・声を端末の外へ送ることはありません。" +
                    "通信するのは、モデルのダウンロード、「更新を確認」を押したとき、" +
                    "文字の認識に使う Google の ML Kit が利用の指標（端末の機種、OSとアプリの版、処理の時間など。画像や文字は含まない）を" +
                    " Google へ送るときです。",
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { openUrl(context, PRIVACY_URL) }, contentPadding = PaddingValues(0.dp)) {
                    Text("プライバシーポリシー", style = MaterialTheme.typography.labelMedium)
                }
                TextButton(
                    onClick = { openUrl(context, "https://developers.google.com/ml-kit/android-data-disclosure") },
                    contentPadding = PaddingValues(start = 16.dp),
                ) { Text("ML Kit が送るもの", style = MaterialTheme.typography.labelMedium) }
            }

            Text("使っているモデル（端末がダウンロードする）", style = MaterialTheme.typography.titleSmall)
            MODELS.forEach { m ->
                Column {
                    Text("${m.name}（${m.role}）", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("作者：${m.author}", style = MaterialTheme.typography.bodySmall)
                    Text("ライセンス：${m.license}", style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = { openUrl(context, m.url) }, contentPadding = PaddingValues(0.dp)) {
                            Text("配布元", style = MaterialTheme.typography.labelSmall)
                        }
                        m.text?.let { t ->
                            TextButton(onClick = { shown = t }, contentPadding = PaddingValues(start = 16.dp)) {
                                Text("ライセンスの全文", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            }

            Text("アプリに含まれるソフトウェア", style = MaterialTheme.typography.titleSmall)
            SOFTWARE.forEach { s ->
                Column {
                    Text(s.name, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("ライセンス：${s.license}", style = MaterialTheme.typography.bodySmall)
                }
            }
            Text("ライセンスの全文", style = MaterialTheme.typography.titleSmall)
            LicenseText.entries.filter { it != LicenseText.OPENRAIL && it != LicenseText.FUNASR }.forEach { t ->
                TextButton(onClick = { shown = t }, contentPadding = PaddingValues(0.dp)) {
                    Text(t.title, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    shown?.let { LicenseTextDialog(it) { shown = null } }
}

/** 同梱したライセンス文を全画面で出す。大きな文（数MB）もあるので、行ごとに並べて見える分だけ描く。 */
@Composable
fun LicenseTextDialog(text: LicenseText, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val lines by produceState<List<String>?>(null, text) {
        value = withContext(Dispatchers.IO) {
            context.assets.open("licenses/${text.asset}").bufferedReader(Charsets.UTF_8).readLines()
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(Modifier.padding(16.dp)) {
                Row {
                    Text(text.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onDismiss) { Text("閉じる") }
                }
                val content = lines
                if (content == null) {
                    Text("読み込んでいます…", style = MaterialTheme.typography.bodySmall)
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(content) { line ->
                            Text(line, fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 14.sp)
                        }
                    }
                }
            }
        }
    }
}
