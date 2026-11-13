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
 * 操作の欄：開始・停止、真ん中の区切るボタン（周りの輪がマイクの音量）、表示モード、自分と相手の言語、ログ、翻訳モデル、状態の表示。
 * 区切るボタンは対面の2人のどちらからも押しやすいよう、欄の真ん中に丸く置く。
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
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                val ready = state.asr == AsrState.Ready && model is ModelState.Ready
                if (state.listening) {
                    OutlinedButton(onClick = actions.stop) { Text("停止") }
                } else {
                    Button(onClick = actions.start, enabled = ready) { Text("開始") }
                }
            }
            CutButton(state, actions.cut)
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End)) {
                ViewMode.entries.forEach { mode ->
                    FilterChip(selected = state.viewMode == mode, onClick = { actions.viewMode(mode) }, label = { Text(mode.label) })
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SelfMenu(state.self, actions.self)
            PartnerMenu(state.mode, state.self, actions.partnerMode)
            LogMenu(enabled = state.turns.isNotEmpty(), actions)
            Spacer(Modifier.weight(1f))
            // 翻訳モデルをその場で切り替える（2026-11-06、作者の要望）
            ModelSwitch()
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
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding) { Text("自分：${PartnerMode(self).short()} ▾") }
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
    Box {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding) { Text("相手：${mode.short()} ▾") }
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

/** 自分・相手・ログ・翻訳モデルを1行に並べるので、ボタンの左右の余白を詰める。 */
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
 */
@Composable
private fun CutButton(state: ConversationUiState, onCut: () -> Unit) {
    val level by animateFloatAsState(if (state.listening) state.level else 0f, label = "level")
    val c = MaterialTheme.colorScheme
    val ring = if (state.live != null) c.primary else c.outline
    val track = c.surfaceVariant
    Box(Modifier.padding(horizontal = 8.dp).size(76.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 5.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * level, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        FilledIconButton(
            onClick = onCut,
            enabled = state.listening,
            modifier = Modifier.size(62.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
        ) {
            Text("区切る", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
        }
    }
}
