package app.plamotranslate

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import app.plamotranslate.diagnostics.DiagnosticsSettings
import app.plamotranslate.diagnostics.diagnosticsSummary
import app.plamotranslate.screen.ScreenTranslateSettings
import app.plamotranslate.screen.screenTranslateSummary

/**
 * 設定（docs/設計/アーキテクチャ.md「設定の画面」）。トップに項目のまとまりを並べ、押すとそのページへ進む階層にする
 * （2026-11-13、作者の指摘「設定のページが長大すぎる」。それまでは全部を1ページに並べていた）。
 */
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

/** 設定のまとまり。何についての設定かで分ける（docs/設計/アーキテクチャ.md「設定の画面」）。 */
private enum class SettingsGroup(val label: String) {
    /** 全機能で共通。 */
    TRANSLATION("翻訳"),

    /** 機能ごと。機能ごとの設定が増えたら、ここにページを足す（カメラ翻訳・会話翻訳など）。 */
    FEATURES("機能"),

    /** ふつうは触らない調整と、不具合を調べるためのもの。 */
    DETAIL("詳細"),

    /** アプリそのもの。 */
    APP("アプリ"),
}

/**
 * 設定のページ。題・まとまり・トップに添える今の状態の一言（[summary]）・中身（[content]）を、ページごとに1つの定義にする。
 * ページを足すときは、ここに1つ足すだけでよい。トップでの並びは、この定義の順。
 */
private enum class SettingsPage(
    val title: String,
    val group: SettingsGroup,
    val summary: @Composable () -> String,
    val content: @Composable () -> Unit,
) {
    MODEL("翻訳モデル", SettingsGroup.TRANSLATION, { modelSummary() }, { ModelPage() }),
    SCREEN("画面翻訳", SettingsGroup.FEATURES, { screenTranslateSummary() }, { ScreenTranslateSettings() }),
    SPEECH("読み上げ", SettingsGroup.FEATURES, { speechSummary() }, { SpeechCard() }),
    PERFORMANCE("性能", SettingsGroup.DETAIL, { "同時に訳す数、スレッド、速さを測る" }, {
        PerformanceCard(EngineHolder.state.collectAsStateWithLifecycle().value)
    }),
    DIAGNOSTICS("不具合の記録", SettingsGroup.DETAIL, { diagnosticsSummary() }, { DiagnosticsSettings() }),
    APPEARANCE("画面の色", SettingsGroup.APP, { ThemePreferences.mode.collectAsStateWithLifecycle().value.label }, { AppearancePage() }),
    ABOUT("このアプリについて", SettingsGroup.APP, { "版と更新、通信とプライバシー" }, { AboutPage() }),
    LICENSES("ライセンス", SettingsGroup.APP, { "使っているモデルとソフトウェア" }, { LicensesCard() }),
}

/** 設定の画面。トップ（[page] が null）と各ページを切り替える。戻る操作は [back] の1か所で決める。 */
@Composable
private fun SettingsScreen(onBack: () -> Unit) {
    var page by rememberSaveable { mutableStateOf<SettingsPage?>(null) }
    fun back() {
        if (page != null) page = null else onBack()
    }
    BackHandler(enabled = page != null) { back() }
    // ページごとに上から見せるので、スクロールの位置はページごとに持つ
    Scaffold { padding -> key(page) {
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { back() }) { Text("←", fontSize = 22.sp) }
                Text(page?.title ?: "設定", style = MaterialTheme.typography.titleLarge)
            }
            page?.content?.invoke() ?: SettingsHome { page = it }
        }
    } }
}

/** 設定のトップ。まとまりごとに見出しを付け、各ページを1行で、今の状態の一言と並べる。 */
@Composable
private fun SettingsHome(open: (SettingsPage) -> Unit) {
    SettingsPage.entries.groupBy { it.group }.forEach { (group, pages) ->
        Text(group.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Card(modifier = Modifier.fillMaxWidth()) {
            pages.forEachIndexed { i, p ->
                if (i > 0) HorizontalDivider()
                Column(
                    Modifier.fillMaxWidth().clickable { open(p) }.padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Text(p.title, style = MaterialTheme.typography.titleMedium)
                    Text(p.summary(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

/** トップの「翻訳モデル」の行に添える一言。 */
@Composable
private fun modelSummary(): String = when (val model = EngineHolder.state.collectAsStateWithLifecycle().value) {
    is ModelState.Ready -> "${model.model.label}・${if (model.engine.usesGpu) "GPU" else "CPU"}で動作"
    ModelState.Loading -> "読み込み中"
    else -> "使えません"
}

/** トップの「読み上げ」の行に添える一言。 */
@Composable
private fun speechSummary(): String {
    val context = LocalContext.current
    return remember { SpeechPreferences(context).engine.label }
}

/** 「翻訳モデル」のページ。 */
@Composable
private fun ModelPage() {
    val context = LocalContext.current
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    ModelStatus(model, onRetry = { EngineHolder.ensureLoaded(context.applicationContext) })
    ModelChooser(model)
}

/** 画面の色：端末に合わせる・ライト（さえずりの紙）・ダーク（夜の鳥）。選ぶとすぐ変わる。 */
@Composable
private fun AppearancePage() {
    val context = LocalContext.current
    val mode by ThemePreferences.mode.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ThemeMode.entries.forEach { m ->
            FilterChip(selected = mode == m, onClick = { ThemePreferences.set(context, m) }, label = { Text(m.label) })
        }
        Text(
            "ライトは生成りの紙と墨の「さえずりの紙」、ダークは紺の地にコーラルとミントの「夜の鳥」。" +
                "画面翻訳の浮遊ボタンとメニューも同じ色になる。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 「このアプリについて」のページ。 */
@Composable
private fun AboutPage() {
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "" }
    AboutCard(version)
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
