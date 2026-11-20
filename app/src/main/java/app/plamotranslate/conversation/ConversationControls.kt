package app.plamotranslate.conversation

import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.models.TranslationModel
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
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.LocalContentColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
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
internal fun Controls(state: ConversationUiState, model: ModelState<TranslationModel>, actions: ConversationActions) {
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
                SelfMenu(state.self, state.languages, actions.self)
                PartnerMenu(state.mode, state.partner.takeIf { state.partnerFound }, state.self, state.languages, actions.partnerMode)
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
            state.canCut -> "聞き取り中…（話し終わると訳が確定します）"
            state.listening -> "聞いています（${state.self.label} ⇄ ${partnerLabel(state)}）"
            else -> "「開始」を押して話してください（${state.self.label} ⇄ ${partnerLabel(state)}）"
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
 * 選べるのは、設定で選んだ音声認識モデルが聞ける言語だけ（[app.plamotranslate.pipeline.speech.SpeechModel.languages]）。
 */
@Composable
private fun SelfMenu(self: Language, languages: List<Language>, onSelect: (Language) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding, modifier = Modifier.fillMaxWidth()) {
            Text("自分：${PartnerMode(self).short()} ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.forEach { lang ->
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
 * 自分と同じ言語は出さない（[PartnerMode.choices]）。自動で見つけた相手（[found]）は「英語（自動）」のように出す。
 */
@Composable
private fun PartnerMenu(mode: PartnerMode, found: Language?, self: Language, languages: List<Language>, onSelect: (PartnerMode) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.fillMaxWidth()) {
        OutlinedButton(onClick = { open = true }, contentPadding = MenuButtonPadding, modifier = Modifier.fillMaxWidth()) {
            // 自動で相手を見つけたら、何に決まったかをボタンに出す（自動のままだと利用者から見えない。2026-11-15、作者の指摘）
            val label = found?.let { "${PartnerMode(it).short()}（自動）" } ?: mode.short()
            Text("相手：$label ▾", maxLines = 1)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            PartnerMode.choices(self, languages).forEach { m ->
                val label = m.label()
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
 * 区切れるとき（聞いていて、話し中のカードがある。[ConversationUiState.canCut]）だけ押せ、名前は「区切る」。区切れないときは
 * 押せず、話すよう促す語（「話して」）を出す。周りの輪はマイクの音量で、話し中のカードがある間は色を変える。
 * 名前は自分と相手の両方の言語で出す。対面では相手の言語を上に逆さに、1画面では自分の言語の下に小さく出す。
 */
@Composable
private fun CutButton(state: ConversationUiState, onCut: () -> Unit) {
    val level by animateFloatAsState(if (state.listening) state.level else 0f, label = "level")
    val c = MaterialTheme.colorScheme
    val ring = if (state.canCut) c.primary else c.outline
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
            enabled = state.canCut,
            modifier = Modifier.size(112.dp),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
        ) {
            val self = cutLabel(state.self, state.canCut)
            val partner = cutLabel(state.partner, state.canCut)
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (state.viewMode == ViewMode.FACE_TO_FACE) {
                    // 対面では相手が向かいから読むので、相手の言語を上に逆さに出す
                    CutLabel(partner, MaterialTheme.typography.titleMedium, bold = true, modifier = Modifier.rotate(180f))
                    HorizontalDivider(Modifier.width(56.dp).padding(vertical = 4.dp), color = LocalContentColor.current.copy(alpha = 0.5f))
                    CutLabel(self, MaterialTheme.typography.titleMedium, bold = true)
                } else {
                    CutLabel(self, MaterialTheme.typography.titleLarge, bold = true)
                    CutLabel(partner, MaterialTheme.typography.labelLarge, bold = false)
                }
            }
        }
    }
}

/**
 * 区切るボタンの名前の1行。丸の中に収まる幅（[CUT_LABEL_WIDTH]）に1行で出し、収まらない名前（言語によって長い語。
 * 「Sprechen」など）は文字を小さくして全部見せる（はみ出すと丸に切られて読めない）。色はボタンの文字の色（押せないときは薄い）。
 */
@Composable
private fun CutLabel(text: String, style: TextStyle, bold: Boolean, modifier: Modifier = Modifier) {
    BasicText(
        text,
        modifier.width(CUT_LABEL_WIDTH),
        style = style.copy(
            color = LocalContentColor.current,
            fontWeight = if (bold) FontWeight.Bold else style.fontWeight,
            textAlign = TextAlign.Center,
        ),
        maxLines = 1,
        autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = style.fontSize, stepSize = 0.5.sp),
    )
}

/** 区切るボタン（直径112dp）の名前の幅。中心から上下20dpほどの行でも丸に切られない幅にする。 */
private val CUT_LABEL_WIDTH = 84.dp

/**
 * 相手の言語の表示。自動で見つけた相手は、誤っていたら選び直せるように「自動で判定」と出す。まだ相手が話していなければ判定中。
 */
private fun partnerLabel(state: ConversationUiState): String = when {
    state.partner == null -> "相手の言語は話したら判定"
    state.partnerFound -> "${state.partner.label}（自動で判定）"
    else -> state.partner.label
}

/**
 * 区切るボタンの、その言語での名前。相手も押すので、自分と相手の両方の言語で出す（作者の要望）。
 * 区切れるとき（[canCut]）は「今話し終えたので次へ」が伝わる短い語、区切れないときは話すよう促す語にする。どちらも丸の中に
 * 大きく出せるよう、「区切る」と同じくらいの長さの語を選ぶ（長い語は文字を小さくして収める（[CutLabel]）が、読みにくくなる）。
 * 表に無い言語と、まだ決まっていない相手の言語（null）は英語で代える。
 */
private fun cutLabel(language: Language?, canCut: Boolean): String {
    val (cut, speak) = when (language) {
        Language.JA -> "区切る" to "話して"
        Language.KO -> "여기까지" to "말하세요"
        Language.ZH -> "说完了" to "请说"
        Language.ZH_HANT -> "說完了" to "請說"
        Language.RU -> "Готово" to "Говорите"
        Language.HI -> "हो गया" to "बोलिए"
        Language.FR -> "Terminé" to "Parlez"
        Language.ES -> "Listo" to "Hable"
        Language.DE -> "Fertig" to "Sprechen"
        else -> "Done" to "Speak"
    }
    return if (canCut) cut else speak
}
