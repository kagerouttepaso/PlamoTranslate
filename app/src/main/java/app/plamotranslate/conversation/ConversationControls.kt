package app.plamotranslate.conversation

import app.plamotranslate.pipeline.conversation.CONVERSATION_LANGUAGES
import app.plamotranslate.pipeline.conversation.PartnerMode
import app.plamotranslate.pipeline.conversation.conversationLabel
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.plamotranslate.ModelState
import app.plamotranslate.ModelSwitch
import app.plamotranslate.llm.Language

/*
 * 会話翻訳の操作の欄とメニュー（docs/設計/会話翻訳.md「画面」）。
 */

/**
 * 操作の欄。3つの列に分け、真ん中に大きな丸い区切るボタン（周りの輪がマイクの音量）を置く。
 * 区切るボタンは会話中にいちばん押すので、対面の2人のどちらからも押しやすいよう、欄の高さいっぱいに大きくする。
 * - 左の列：話すこと（開始・停止、自分と相手の言語）
 * - 右の列：見せ方とそのほか（対面・1画面、ログ、翻訳モデル）
 * その下に状態を1行出す。
 */
@Composable
internal fun Controls(state: ConversationUiState, model: ModelState, actions: ConversationActions) {
    // 操作欄は地の色と分け、上下の線で会話の一覧と区切る（対面では2人の面の境目にもなる）
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Column(
        Modifier.fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val ready = state.asr == AsrState.Ready && model is ModelState.Ready
                if (state.listening) {
                    OutlinedButton(onClick = actions.stop, modifier = Modifier.fillMaxWidth()) { Text("停止") }
                } else {
                    Button(onClick = actions.start, enabled = ready, modifier = Modifier.fillMaxWidth()) { Text("開始") }
                }
                SelfMenu(state.self, actions.self)
                PartnerMenu(state.mode, state.self, actions.partnerMode)
            }
            CutButton(state, actions.cut)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.End) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    ViewMode.entries.forEach { mode ->
                        FilterChip(selected = state.viewMode == mode, onClick = { actions.viewMode(mode) }, label = { Text(mode.label) })
                    }
                }
                LogMenu(enabled = state.turns.isNotEmpty(), actions)
                // 翻訳モデルをその場で切り替える（2026-11-06、作者の要望）
                ModelSwitch()
            }
        }
        val status = when {
            state.asr is AsrState.Missing -> "音声認識のモデルがありません。トップ画面に戻ると出る「モデルの準備」でダウンロードしてください"
            state.asr is AsrState.Failed -> state.asr.message
            state.asr == AsrState.Loading -> "音声認識を準備中…"
            model is ModelState.Missing -> "翻訳モデルがありません。トップ画面に戻ると出る「モデルの準備」か、設定の画面でダウンロードしてください"
            model is ModelState.Failed -> model.message
            model is ModelState.Loading -> "翻訳モデルを準備中…"
            state.error != null -> state.error
            state.live != null -> "聞き取り中…（話し終わると訳が確定します）"
            state.listening -> "聞いています（${state.self.label} ⇄ ${state.partner.label}）"
            else -> "「開始」を押して話してください（${state.self.label} ⇄ ${state.partner.label}）"
        }
        Text(status, style = MaterialTheme.typography.labelMedium)
        if (state.asr is AsrState.Missing || state.asr is AsrState.Failed) {
            TextButton(onClick = actions.retry) { Text("読み直す") }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/**
 * 自分の言語の選択（2026-11-08、作者の要望で日本語以外も選べるようにした）。
 * 選べるのは音声認識が聞き分けられる言語だけ（[CONVERSATION_LANGUAGES]）。
 */
@Composable
private fun SelfMenu(self: Language, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding, modifier = Modifier.fillMaxWidth()) {
            Text("自分：${PartnerMode(self).short()} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            CONVERSATION_LANGUAGES.forEach { lang ->
                DropdownMenuItem(
                    text = { Text(if (lang == self) "✓ ${lang.conversationLabel}" else lang.conversationLabel) },
                    onClick = { open = false; onSelect(lang) },
                )
            }
        }
    }
}

/**
 * 相手の言語の選択。中国語を足して5つになり1行に並ばないので、チップからメニューにした（2026-11-08）。
 * 自分と同じ言語は出さない（[PartnerMode.choices]）。
 */
@Composable
private fun PartnerMenu(mode: PartnerMode, self: Language, onSelect: (PartnerMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding, modifier = Modifier.fillMaxWidth()) {
            Text("相手：${mode.short()} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PartnerMode.choices(self).forEach { m ->
                val label = m.label(self)
                DropdownMenuItem(
                    text = { Text(if (m == mode) "✓ $label" else label) },
                    onClick = { open = false; onSelect(m) },
                )
            }
        }
    }
}

/** 自分・相手は左の列の幅に収めるので、ボタンの左右の余白を詰める。 */
private val MenuButtonPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

/** 会話ログの共有・保存と、画面の発話の消去。 */
@Composable
private fun LogMenu(enabled: Boolean, actions: ConversationActions) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }, enabled = enabled) { Text("ログ ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("共有（ほかのアプリへ送る）") }, onClick = { open = false; actions.shareLog() })
            DropdownMenuItem(text = { Text("ファイルに保存") }, onClick = { open = false; actions.saveLog() })
            DropdownMenuItem(text = { Text("画面の発話を消去") }, onClick = { open = false; actions.clear() })
        }
    }
}

/**
 * 区切るボタン。押すと、今話している分を確定して次のカードにする（聞くのは続ける。docs/設計/会話翻訳.md「カードの区切り方」）。
 * 周りの輪はマイクの音量で、話していると判定している間は色を変える。聞いている間だけ押せる。
 * 名前は自分と相手の両方の言語で出す。対面では相手の言語を上に逆さに、1画面では自分の言語の下に小さく出す。
 */
@Composable
private fun CutButton(state: ConversationUiState, onCut: () -> Unit) {
    val level by animateFloatAsState(if (state.listening) state.level else 0f, label = "level")
    val c = MaterialTheme.colorScheme
    val ring = if (state.live != null) c.primary else c.outline
    val track = c.surfaceVariant
    Box(Modifier.padding(horizontal = 10.dp).size(132.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 7.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * level, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        FilledIconButton(
            onClick = onCut,
            enabled = state.listening,
            modifier = Modifier.size(112.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
        ) {
            val self = cutLabel(state.self)
            val partner = cutLabel(state.partner)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.viewMode == ViewMode.FACE_TO_FACE) {
                    // 対面では相手が向かいから読むので、相手の言語を上に逆さに出す
                    Text(partner, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.rotate(180f))
                    HorizontalDivider(Modifier.width(56.dp).padding(vertical = 4.dp), color = c.onPrimary.copy(alpha = 0.5f))
                    Text(self, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                } else {
                    Text(self, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(partner, style = MaterialTheme.typography.labelLarge)
                }
            }
        }
    }
}

/**
 * 区切るボタンの、その言語での名前。相手も押すので、自分と相手の両方の言語で出す（作者の要望）。
 * 「今話し終えたので次へ」が伝わる短い語にする。会話で選べる言語（[app.plamotranslate.pipeline.conversation.CONVERSATION_LANGUAGES]）だけを持つ。
 */
private fun cutLabel(language: Language): String = when (language) {
    Language.KO -> "여기까지"
    Language.EN -> "Done"
    Language.ZH -> "说完了"
    Language.ZH_HANT -> "說完了"
    else -> "区切る"
}
