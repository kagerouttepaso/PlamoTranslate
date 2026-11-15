package app.plamotranslate

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.plamotranslate.models.DownloadItem
import app.plamotranslate.models.DownloadQueueList
import app.plamotranslate.models.DownloadStatus
import app.plamotranslate.models.ModelBundle
import app.plamotranslate.models.ModelDownloads
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.pipeline.speech.SpeechModel

/**
 * 初回の準備の画面（2026-11-06、APKを配布するため。2026-11-15、作者の要望で、モデルを選んでまとめて取る形にした）。
 * 翻訳・音声認識・読み上げのモデルを選び（既定は小さい方）、必須の組と合わせて待ち行列に足す。取っている間は、
 * 足した組を上から並べて進み具合を出す。必須の組・翻訳・音声認識がそろい、足した組を取り終えたらトップ画面に移る（[MainActivity]）。
 * 選んだモデルは、それぞれの設定（翻訳モデル・音声認識モデル・読み上げ）に書く。後から設定の画面で替えられる。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SetupScreen(items: List<DownloadItem>) {
    val context = LocalContext.current
    var translation by rememberSaveable { mutableStateOf(TranslationModel.SMALL) }
    var recognition by rememberSaveable { mutableStateOf(SpeechModel.SENSE_VOICE) }
    var speech by rememberSaveable { mutableStateOf(SpeechEngine.SYSTEM) }
    var askConsent by remember { mutableStateOf(false) }
    val active = items.any { it.status is DownloadStatus.Waiting || it.status is DownloadStatus.Running }
    val bundles = listOfNotNull(
        ModelBundle.BASE,
        ModelBundle.of(translation),
        ModelBundle.of(recognition),
        ModelBundle.SPEECH.takeIf { speech == SpeechEngine.SUPERTONIC },
    )

    fun start() {
        TranslationPreferences(context).model = translation
        RecognitionPreferences(context).model = recognition
        SpeechPreferences(context).engine = speech
        ModelDownloads.enqueue(context, bundles)
    }

    val scheme = MaterialTheme.colorScheme
    Scaffold { padding ->
        Column(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp, vertical = 12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BrandName(Modifier.fillMaxWidth().padding(top = 4.dp))
            Text("モデルの準備", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (!active) {
                // 役割の面で見分ける：音声認識は話すこと（primary）、翻訳は訳（secondary）、読み上げと必須は地の面
                // （docs/設計/デザイン言語.md「色」）
                ChoiceCard(
                    "音声認識", SpeechModel.entries, recognition, { recognition = it }, { it.label },
                    { "${it.note}・約${ModelBundle.of(it).size / 1_000_000}MB" },
                    scheme.primaryContainer, scheme.onPrimaryContainer, scheme.primary,
                )
                ChoiceCard(
                    "翻訳", TranslationModel.entries, translation, { translation = it }, { it.label },
                    { "${it.note}・約${ModelBundle.of(it).size / 1_000_000}MB" },
                    scheme.secondaryContainer, scheme.onSecondaryContainer, scheme.secondary,
                )
                ChoiceCard(
                    "読み上げ", SpeechEngine.entries, speech, { speech = it }, { it.label },
                    { if (it == SpeechEngine.SUPERTONIC) "${it.note}・約${ModelBundle.SPEECH.size / 1_000_000}MB" else it.note },
                    scheme.surfaceContainerHigh, scheme.onSurface, scheme.primary,
                )
                Text(
                    "必須：${ModelBundle.BASE.label}・約${ModelBundle.BASE.size / 1_000_000}MB",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                val total = remember(bundles, items) { bundles.filterNot { it.isPresent(context) }.sumOf { it.size } }
                Button(
                    onClick = {
                        if (speech == SpeechEngine.SUPERTONIC && !SpeechPreferences(context).supertonicAgreed) askConsent = true else start()
                    },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    shape = RoundedCornerShape(28.dp),
                ) { Text("ダウンロード（約${total / 1_000_000}MB）", style = MaterialTheme.typography.titleMedium) }
                Text(
                    "Wi-Fiでの実行をおすすめします。後から設定の画面で替えられます。",
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.onSurfaceVariant,
                )
                LicenseAgreementNote()
            }
            if (items.isNotEmpty()) {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = scheme.surfaceContainerHigh, contentColor = scheme.onSurface),
                ) {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DownloadQueueList(items)
                    }
                }
                if (active) {
                    Text("ダウンロード中はこの画面を開いたままにしてください", style = MaterialTheme.typography.labelMedium)
                    OutlinedButton(onClick = ModelDownloads::cancel) { Text("中止") }
                }
            }
        }
    }
    if (askConsent) {
        SupertonicConsentDialog(
            downloading = true,
            onAgree = {
                askConsent = false
                SpeechPreferences(context).supertonicAgreed = true
                start()
            },
            onDismiss = { askConsent = false },
        )
    }
}

/**
 * 1つの種類のモデルの選択。役割の面（[container]）に、名前・選択肢のチップ・選んだものの説明を置く。
 * 選んだチップは役割の色（[accent]）の縁と文字で示し、塗りは使わない（強い塗りは「ダウンロード」の1つにする）。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChoiceCard(
    title: String,
    options: List<T>,
    selected: T,
    onSelect: (T) -> Unit,
    label: (T) -> String,
    note: (T) -> String,
    container: Color,
    content: Color,
    accent: Color,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = container, contentColor = content),
    ) {
        Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                options.forEach { option ->
                    val on = option == selected
                    FilterChip(
                        selected = on,
                        onClick = { onSelect(option) },
                        label = { Text(label(option), maxLines = 1, fontWeight = if (on) FontWeight.Bold else null) },
                        colors = FilterChipDefaults.filterChipColors(
                            containerColor = Color.Transparent,
                            labelColor = content,
                            selectedContainerColor = MaterialTheme.colorScheme.surface,
                            selectedLabelColor = accent,
                        ),
                        border = FilterChipDefaults.filterChipBorder(
                            enabled = true,
                            selected = on,
                            borderColor = content.copy(alpha = 0.4f),
                            selectedBorderColor = accent,
                            selectedBorderWidth = 2.dp,
                        ),
                    )
                }
            }
            Text(note(selected), style = MaterialTheme.typography.bodySmall)
        }
    }
}
