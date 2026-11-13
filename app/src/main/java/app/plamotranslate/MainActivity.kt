package app.plamotranslate

import android.Manifest
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.camera.CameraTranslateActivity
import app.plamotranslate.conversation.ConversationActivity
import app.plamotranslate.models.DownloadState
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.needsSetup
import app.plamotranslate.screen.ScreenTranslateService
import androidx.compose.ui.res.stringResource


/**
 * トップ画面（2026-11-06に整理）。使う頻度の順（作者：会話→カメラ→画面→テキスト）に、
 * よく使う機能ほど大きいタイルにする。モデルの選択は設定の画面、テキスト翻訳は別の画面に分けた。
 * 初回だけ、モデルのダウンロードの画面を全体に出す。
 */
class MainActivity : ComponentActivity() {
    private val captureConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val data = result.data
        if (result.resultCode == RESULT_OK && data != null) {
            ScreenTranslateService.start(this, result.resultCode, data)
            // 翻訳したい画面へ移ってもらうため、自分は背面へ下がる
            moveTaskToBack(true)
        } else {
            Toast.makeText(this, "画面共有が許可されませんでした", Toast.LENGTH_SHORT).show()
        }
    }
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // ファイルの大きさを見るだけなので、画面を出す前に数え直す（準備画面が一瞬出ないように）
        ModelDownloads.refresh(this)
        setContent {
            AppTheme {
                val download by ModelDownloads.state.collectAsStateWithLifecycle()
                if (!download.needsSetup()) {
                    LaunchedEffect(Unit) { EngineHolder.ensureLoaded(applicationContext) }
                    HomeScreen(onToggleScreenTranslate = ::toggleScreenTranslate)
                } else {
                    SetupScreen(download, onCompleted = { EngineHolder.ensureLoaded(applicationContext) })
                }
            }
        }
        if (savedInstanceState == null) handleShortcut(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleShortcut(intent)
    }

    /** アイコンの長押しメニューとクイック設定のタイルから「画面翻訳」で来たとき。 */
    private fun handleShortcut(intent: Intent?) {
        if (intent?.action != ACTION_SCREEN_TRANSLATE) return
        if (ModelDownloads.state.value.needsSetup()) return
        toggleScreenTranslate()
    }

    private fun toggleScreenTranslate() {
        if (ScreenTranslateService.running.value) ScreenTranslateService.stop(this) else startScreenTranslate()
    }

    /** 重ね表示の許可→（通知の許可）→画面共有の許可の順に取り、サービスを始める。 */
    private fun startScreenTranslate() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "「他のアプリの上に重ねて表示」を許可してから戻ってください", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        // Android 14以降は「画面全体」を既定にする（アプリ単位の共有だと別アプリへ移ったとき写らない）
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            mpm.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            mpm.createScreenCaptureIntent()
        }
        captureConsent.launch(intent)
    }

    companion object {
        const val ACTION_SCREEN_TRANSLATE = "app.plamotranslate.action.SCREEN_TRANSLATE"
    }
}

@Composable
private fun HomeScreen(onToggleScreenTranslate: () -> Unit) {
    val context = LocalContext.current
    val screenRunning by ScreenTranslateService.running.collectAsStateWithLifecycle()
    val model by EngineHolder.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandHeader(
                modelLabel = model.shortLabel(),
                onOpenSettings = { context.startActivity(Intent(context, SettingsActivity::class.java)) },
            )

            // 4つの機能は同じ重さの面にし、役割の色で見分ける。よく使う会話翻訳は大きさで先に置く
            // （docs/設計/デザイン言語.md「トップ画面」）
            val scheme = MaterialTheme.colorScheme
            Tile(
                icon = FeatureIcons.Conversation,
                title = "会話翻訳",
                container = scheme.primaryContainer,
                content = scheme.onPrimaryContainer,
                accent = scheme.primary,
                large = true,
                modifier = Modifier.fillMaxWidth().weight(1.2f),
                onClick = { context.startActivity(Intent(context, ConversationActivity::class.java)) },
            )
            Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Tile(
                    icon = FeatureIcons.Camera,
                    title = "カメラ翻訳",
                    container = scheme.secondaryContainer,
                    content = scheme.onSecondaryContainer,
                    accent = scheme.secondary,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = { context.startActivity(Intent(context, CameraTranslateActivity::class.java)) },
                )
                Tile(
                    icon = FeatureIcons.Screen,
                    title = "画面翻訳",
                    container = scheme.tertiaryContainer,
                    content = scheme.onTertiaryContainer,
                    accent = scheme.tertiary,
                    running = screenRunning,
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                    onClick = onToggleScreenTranslate,
                )
            }
            Tile(
                icon = FeatureIcons.Text,
                title = "テキスト翻訳",
                container = scheme.surfaceContainerHigh,
                content = scheme.onSurface,
                accent = scheme.primary,
                modifier = Modifier.fillMaxWidth().height(64.dp),
                row = true,
                onClick = { context.startActivity(Intent(context, TextTranslateActivity::class.java)) },
            )
        }
    }
}

/**
 * 上の帯：アプリのアイコンを印にし、名前を「Mocking」と主の色の「word」に分けて組む。説明の副題は付けない。
 * 右は今の翻訳モデルの状態と設定の入口。アイコンは ic_launcher の絵をそのまま使い、アイコンを変えれば印も変わる。
 */
@Composable
private fun BrandHeader(modelLabel: String, onOpenSettings: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
        AppMark(Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Text(
            buildAnnotatedString {
                append("Mocking")
                withStyle(SpanStyle(color = scheme.primary)) { append("word") }
            },
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = scheme.onSurface,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Surface(
            onClick = onOpenSettings,
            shape = CircleShape,
            color = scheme.surfaceContainerHigh,
            contentColor = scheme.onSurface,
        ) {
            Row(
                Modifier.padding(start = 12.dp, end = 10.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(modelLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1)
                Spacer(Modifier.width(6.dp))
                Icon(FeatureIcons.Settings, contentDescription = "設定", modifier = Modifier.size(20.dp))
            }
        }
    }
}

/** アプリのアイコン（アダプティブアイコンの背景と前景）を、角の丸い四角に切り出して描く。前景は108の格子の真ん中72が見える範囲。 */
@Composable
private fun AppMark(modifier: Modifier) {
    BoxWithConstraints(modifier.clip(RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
        val full = maxWidth * (108f / 72f)
        Image(painterResource(R.drawable.ic_launcher_background), contentDescription = null, modifier = Modifier.requiredSize(full))
        Image(painterResource(R.drawable.ic_launcher_foreground), contentDescription = null, modifier = Modifier.requiredSize(full))
    }
}

/**
 * 機能のタイル。左上に名前、右下に役割の明るい色で大きなアイコンを置く。説明の文は付けない（名前とアイコンで伝える）。
 * 動いている機能（画面翻訳）は、役割の色の枠と「動作中」で見分ける。
 */
@Composable
private fun Tile(
    icon: ImageVector,
    title: String,
    container: Color,
    content: Color,
    /** アイコンと枠の色。面の上で光る、役割の明るい色（docs/設計/デザイン言語.md「色」）。 */
    accent: Color,
    modifier: Modifier,
    large: Boolean = false,
    row: Boolean = false,
    running: Boolean = false,
    onClick: () -> Unit,
) {
    Card(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(if (large) 28.dp else 24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
        border = if (running) BorderStroke(3.dp, accent) else null,
    ) {
        if (row) {
            Row(Modifier.fillMaxSize().padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, contentDescription = null, tint = accent, modifier = Modifier.size(26.dp))
                Spacer(Modifier.width(14.dp))
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
            return@Card
        }
        Box(Modifier.fillMaxSize()) {
            // アイコンは右下に大きく薄く置き、タイルの角で見切れさせる（空いた面を、何の機能かを示す模様にする）。
            // 暗い面には面の文字の色を、明るい面には役割の色を薄く重ねる（暗い面に色相の違う役割の色を重ねると、茶に濁る）
            val markColor = if (container.luminance() < 0.3f) content.copy(alpha = 0.14f) else accent.copy(alpha = 0.22f)
            Icon(
                icon,
                contentDescription = null,
                tint = markColor,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = if (large) 56.dp else 40.dp, y = if (large) 56.dp else 40.dp)
                    .size(if (large) 280.dp else 180.dp),
            )
            Column(Modifier.align(Alignment.TopStart).padding(if (large) 24.dp else 20.dp)) {
                Text(title, fontSize = if (large) 30.sp else 22.sp, fontWeight = FontWeight.Bold, maxLines = 1)
                if (running) {
                    Text("● 動作中", style = MaterialTheme.typography.labelLarge, color = accent, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}


/** 右上の小さな表示。押すと設定の画面へ。 */
private fun ModelState.shortLabel(): String = when (this) {
    is ModelState.Ready -> "${model.label}・${if (engine.usesGpu) "GPU" else "CPU"}"
    ModelState.Loading -> "読み込み中…"
    is ModelState.Missing -> "モデルなし"
    is ModelState.Failed -> "読み込み失敗"
}

/** 初回だけ出す、モデルのダウンロードの画面（2026-11-06、APKを配布するため）。 */
@Composable
private fun SetupScreen(state: DownloadState, onCompleted: () -> Unit) {
    val context = LocalContext.current
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
            Text("最初にモデルを準備します", style = MaterialTheme.typography.titleMedium)
            when (state) {
                is DownloadState.Idle, is DownloadState.Failed -> {
                    val missing = if (state is DownloadState.Idle) state.missing else (state as DownloadState.Failed).missing
                    val mb = missing.sumOf { it.size } / 1_000_000
                    Text(
                        "翻訳と音声・文字の認識に使うモデル（約${mb}MB）を、最初に1回だけダウンロードします。" +
                            "Wi-Fiでの実行をおすすめします。ダウンロードの後は、通信せずに端末の中だけで動きます。",
                    )
                    if (state is DownloadState.Failed) Text(state.message, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { ModelDownloads.start(context, ModelBundle.REQUIRED, onCompleted) }, enabled = missing.isNotEmpty()) {
                        Text(if (state is DownloadState.Failed) "続きからダウンロード" else "ダウンロード")
                    }
                    LicenseAgreementNote()
                }
                is DownloadState.Running -> {
                    val progress = if (state.total > 0) state.done.toFloat() / state.total else 0f
                    Text(state.current.label)
                    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                    Text(
                        "${state.done / 1_000_000} / ${state.total / 1_000_000} MB　ダウンロード中はこの画面を開いたままにしてください",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                }
                DownloadState.Completed -> Unit
            }
        }
    }
}

/** アイコンの長押しメニュー・クイック設定のタイルから画面翻訳を始める・止めるための入口。 */
fun screenTranslateIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java)
        .setAction(MainActivity.ACTION_SCREEN_TRANSLATE)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
