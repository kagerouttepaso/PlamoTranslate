package app.plamotranslate.desktop

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.conversation.Direction
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.SwapState
import app.plamotranslate.pipeline.conversation.Turn
import app.plamotranslate.pipeline.conversation.conversationLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale

/*
 * 会話翻訳の画面（PC）。Android 版の会話翻訳（`ConversationCards.kt`・`ConversationControls.kt`）の見せ方を、
 * PC で使う場面（机で1人が大きなウィンドウで見る。キーボードとマウス）に合わせて移したもの。
 * - 対面表示は持たない（PC を2人で向かい合って囲まない）。カードは Android の「1画面」の形。
 * - 話し手は話した言語で決める。誤りはカードの「入れ替え」で直す（マウスを乗せると出る）。
 * - 区切るボタンは画面で1つだけの強い操作として、操作の帯の中央に大きく置く。スペースキーでも押せる。
 * - 自分・相手の言語は会話中も選び直せる。
 */

/** カードの一覧の最大の幅。横に広いウィンドウでも、1行が長くなりすぎて読みにくくならないようにする。 */
private val CardListMaxWidth = 960.dp

@Composable
internal fun AudioScreen(
    state: AudioState,
    level: StateFlow<Float>,
    model: ModelStatus,
    presenter: AudioPresenter,
    openSettings: () -> Unit,
) {
    var confirmClear by remember { mutableStateOf(false) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(state.selection.recognition, state.selection.source, state.selection.playbackFrom,
        state.selection.playbackTo, state.automaticAvailable) {
        presenter.prepare()
    }
    Column(
        Modifier.fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                // 区切るのは会話中にいちばん押すので、どこに注目があってもスペースキーで押せるようにする
                if (event.key == Key.Spacebar && event.type == KeyEventType.KeyDown && state.canCut) {
                    presenter.cut()
                    true
                } else {
                    false
                }
            },
    ) {
        TopBar(state, model, presenter, openSettings) { confirmClear = true }
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
            if (state.live.isEmpty() && state.turns.isEmpty()) {
                EmptyHint(state)
            } else {
                Cards(state, level, presenter, Modifier.widthIn(max = CardListMaxWidth).fillMaxSize())
            }
        }
        Controls(state, level, presenter)
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("画面の発話を消去しますか？") },
            text = { Text("保存していない会話の記録は残りません。") },
            confirmButton = { DesktopLink(onClick = { confirmClear = false; presenter.clear() }) { Text("消去") } },
            dismissButton = { DesktopLink(onClick = { confirmClear = false }) { Text("取り消し") } },
        )
    }
}

/** 上の帯：音の出どころの切り替え（左）、会話の記録と翻訳モデル（右）。 */
@Composable
private fun TopBar(state: AudioState, model: ModelStatus, presenter: AudioPresenter, openSettings: () -> Unit, clear: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = DesktopSize.gap), verticalAlignment = Alignment.CenterVertically) {
        Row(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
            AudioSource.entries.forEach { source ->
                DesktopChoice(
                    selected = state.selection.source == source,
                    enabled = state.canConfigure,
                    onClick = { presenter.selectSource(source) },
                    label = { Text(source.label) },
                )
            }
        }
        Spacer(Modifier.weight(1f))
        if (state.selection.source == AudioSource.MICROPHONE) LogMenu(state, presenter, clear)
        DesktopLink(onClick = openSettings) {
            Text(if (model.isError) model.label else "翻訳モデル：${model.label}", maxLines = 1,
                color = if (model.isError) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
}

@Composable
private fun LogMenu(state: AudioState, presenter: AudioPresenter, clear: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DesktopLink(onClick = { open = true }, enabled = state.canExport) { Text("記録 ▾") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            DropdownMenuItem(text = { Text("ファイルに保存") }, onClick = { open = false; presenter.exportLog() })
            DropdownMenuItem(text = { Text("コピー") }, onClick = { open = false; presenter.copyLog() })
            DropdownMenuItem(text = { Text("画面の発話を消去") }, enabled = !state.listening, onClick = { open = false; clear() })
        }
    }
}

/** カードがまだ無いときの案内。何をすれば始まるかだけを出す。 */
@Composable
private fun EmptyHint(state: AudioState) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            when {
                state.listening -> "話してください"
                state.selection.source == AudioSource.PLAYBACK -> "「開始」を押すと、PC で再生している音声を訳します"
                else -> "「開始」を押して話してください"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 新しいカードが下に来る一覧。下から、話し中・書き起こし待ちのカード、確定したカードの順。 */
@Composable
private fun Cards(state: AudioState, level: StateFlow<Float>, presenter: AudioPresenter, modifier: Modifier) {
    val canSwap = state.selection.source == AudioSource.MICROPHONE
    val list = rememberLazyListState()
    // 一番下（最新）を見ているときは、新しいカードが来ても一番下に留まる。LazyColumn は先頭に足された項目より前に見えていた
    // 項目を保つので、そのままだと新しい話し中のカードが見えない位置へ押し出される。過去を読み返している間は動かさない。
    val newest = state.live.lastOrNull()?.let { "live-${it.card}" } ?: state.turns.lastOrNull()?.let { "turn-${it.id}" }
    LaunchedEffect(newest, state.live.size + state.turns.size) {
        // 足された直後は、それまで一番下だった項目が1番目になる。それより上を読んでいるときだけ動かさない
        if (list.firstVisibleItemIndex <= 1) list.scrollToItem(0)
    }
    LazyColumn(
        modifier,
        state = list,
        reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(DesktopSize.gap, Alignment.Bottom),
    ) {
        items(state.live.asReversed(), key = { "live-${it.card}" }) { card ->
            if (card.open) {
                val shown by level.collectAsState()
                LiveCard(card, shown, state.lagSeconds, waiting = false)
            } else {
                LiveCard(card, 0f, 0f, waiting = true)
            }
        }
        items(state.turns.asReversed(), key = { "turn-${it.id}" }) { turn ->
            TurnCard(turn, canSwap) { presenter.swapLanguage(turn.id) }
        }
    }
}

/** 誰の発話かで色を決める。自分は primary、相手は secondary の面（docs/設計/デザイン言語.md「色」）。 */
@Composable
private fun speakerColors(mine: Boolean): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return if (mine) c.primaryContainer to c.onPrimaryContainer else c.secondaryContainer to c.onSecondaryContainer
}

/** 確定したカードの見出し（誰が・何語から何語へ）。 */
private fun cardLabel(direction: Direction): String =
    "${if (direction.mine) "自分" else "相手"}・${direction.source.label} → ${direction.target?.label ?: "（相手の言語を判定中）"}"

/** 出す訳文。失敗したら、そのことを示す文。相手の言語が分かる前の自分の発話は、相手が分かったら訳すと知らせる。 */
private val Turn.shownTranslation: String
    get() = failure?.let { "（翻訳に失敗しました：$it）" }
        ?: if (direction.target == null) "（相手の言語が分かったら訳します）" else translation.ifEmpty { "…" }

/** 認識器が区切った後の待ち時間。話している間に終わったジョブの処理時間は含まない。 */
internal val Turn.statsText: String
    get() = buildString {
        val wait = if (recognizeMs == 0L) "1ms未満" else "${recognizeMs}ms"
        append("区切りから確定まで${wait}（音声区間${String.format(Locale.US, "%.1f", durationMs / 1000.0)}秒）")
        translateMs?.let { append("・翻訳${it}ms") }
    }

/**
 * 確定したカード。自分の発話は右、相手は左に寄せ、話した人の色の面にする。見出し・原文・訳文・かかった時間を縦に並べる。
 * 訳がそろうまで訳文を薄く出す。書き起こし直しを待っている間は原文を薄く出す。
 * 話し手の取り違えは、マウスを乗せると出る「話し手を入れ替える」で直す（Android はカードを横にずらす操作。PC はマウスなので
 * ボタンにする。常に出すと会話の文より目立つので、乗せたときだけ出す）。
 * もう入れ替えられない古いカード（音声を破棄した）は、色を背景色へ少し寄せて落ち着かせる。
 */
@Composable
private fun TurnCard(turn: Turn, canSwap: Boolean, onSwap: () -> Unit) {
    val d = turn.direction
    val (container, content) = speakerColors(d.mine)
    val settled by animateFloatAsState(if (turn.swap == SwapState.EXPIRED) 1f else 0f, label = "settled")
    val surface = lerp(container, MaterialTheme.colorScheme.surface, 0.35f * settled)
    val pending = content.copy(alpha = 0.45f)
    val rewriting = turn.swap == SwapState.REWRITING
    val hover = remember { MutableInteractionSource() }
    val hovered by hover.collectIsHoveredAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (d.mine) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f).hoverable(hover),
            shape = MaterialTheme.shapes.large,
            color = surface,
            contentColor = content,
        ) {
            Column(Modifier.padding(horizontal = DesktopSize.margin, vertical = DesktopSize.gap), verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (rewriting) "${cardLabel(d)}・書き起こし直し中" else cardLabel(d),
                        style = MaterialTheme.typography.labelLarge,
                        color = content.copy(alpha = 0.75f),
                        modifier = Modifier.weight(1f),
                    )
                    if (canSwap && turn.swap == SwapState.AVAILABLE && hovered) {
                        DesktopLink(onClick = onSwap, contentPadding = DesktopSize.linkPadding) {
                            Text("⇄ 話し手を入れ替える", style = MaterialTheme.typography.labelMedium, color = content)
                        }
                    }
                }
                SelectionContainer {
                    Column(verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
                        Text(turn.original, style = DesktopSize.cardSub, color = if (rewriting) pending else content.copy(alpha = 0.85f))
                        Text(
                            turn.shownTranslation,
                            style = DesktopSize.cardMain,
                            color = if (!turn.translated || turn.direction.target == null) pending else content,
                        )
                    }
                }
                Text(turn.statsText, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.55f))
            }
        }
    }
}

/**
 * 話している最中のカード（[waiting] が false）と、閉じて書き起こしを待っているカード（true）。確定したカードと同じ大きさで出し、
 * 地の面に話した人の色の枠を付けて「まだ変わる」ことを示す（Android の `LiveCard`・`WaitingCard`）。
 * 話し中は太い枠と音量のバー、文字がまだ無い間は話し始めからの秒数、認識が1秒以上遅れていればその秒数を出す。
 * 書き起こし待ちは細く薄い枠と「⏳」の見出しにする。話し手が分かる前は中立の色。
 */
@Composable
private fun LiveCard(live: LiveSpeech, level: Float, lagSeconds: Float, waiting: Boolean) {
    val d = live.direction
    val c = MaterialTheme.colorScheme
    val accent = when {
        d == null -> c.outline
        d.mine -> c.primary
        else -> c.secondary
    }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    if (live.text.isEmpty()) {
        LaunchedEffect(live.startedAtMs) {
            while (true) {
                now = System.currentTimeMillis()
                delay(250)
            }
        }
    }
    val seconds = ((now - live.startedAtMs) / 1000).coerceAtLeast(0)
    val who = d?.let { "${if (it.mine) "自分" else "相手"}・${it.source.label}" }
    val status = when {
        waiting -> "⏳ ${who?.let { "$it・" } ?: ""}書き起こし中"
        who == null -> "● 話し中"
        else -> "● $who"
    }
    val shownLevel by animateFloatAsState(level, label = "liveLevel")
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (d?.mine != false) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.88f),
            shape = MaterialTheme.shapes.large,
            color = c.surfaceContainerHigh,
            contentColor = c.onSurface,
            border = BorderStroke(if (waiting) 1.dp else 2.dp, if (waiting) accent.copy(alpha = 0.6f) else accent),
        ) {
            Column(Modifier.padding(horizontal = DesktopSize.margin, vertical = DesktopSize.gap), verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
                Text(status, style = MaterialTheme.typography.labelLarge, color = accent)
                Text(live.text.ifEmpty { "書き起こし中… ${seconds}秒" }, style = DesktopSize.cardSub)
                Text(live.translation.ifEmpty { "…" }, style = DesktopSize.cardMain, color = c.onSurface.copy(alpha = 0.8f))
                if (!waiting && lagSeconds >= 1f) {
                    Text("認識が%.0f秒遅れています".format(lagSeconds), style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
                }
                if (!waiting) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceVariant)) {
                        Box(Modifier.fillMaxWidth(shownLevel).fillMaxHeight().background(accent))
                    }
                }
            }
        }
    }
}

/**
 * 操作の帯。左に話すこと（開始・停止と言語）、中央に区切るボタン、右に再生音の字幕。その下に状態を1行出す。
 * 地の色と分け、上の線でカードの一覧と区切る。
 */
@Composable
private fun Controls(state: AudioState, level: StateFlow<Float>, presenter: AudioPresenter) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = DesktopSize.margin, vertical = DesktopSize.gap), verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                    if (state.listening || state.phase == AudioPhase.STOPPING) {
                        DesktopOutline(onClick = presenter::stop, enabled = state.canStop, modifier = Modifier.widthIn(min = DesktopSize.audioActionWidth)) {
                            Text(if (state.phase == AudioPhase.STOPPING) "確定中…" else "停止")
                        }
                    } else {
                        DesktopButton(onClick = presenter::start, enabled = state.canStart, modifier = Modifier.widthIn(min = DesktopSize.audioActionWidth)) {
                            Text("開始")
                        }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap),
                        verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
                        when (state.selection.source) {
                            AudioSource.MICROPHONE -> {
                                LanguageMenu("自分：${state.selection.self.conversationLabel} ▾", state.selfOptions,
                                    presenter::selectSelf, state.canChooseLanguages) { it.conversationLabel }
                                LanguageMenu("相手：${partnerLabel(state)} ▾", state.partnerOptions,
                                    presenter::selectPartner, state.canChooseLanguages) { it?.conversationLabel ?: "自動（話したら判定）" }
                            }
                            AudioSource.PLAYBACK -> {
                                LanguageMenu("原文：${state.selection.playbackFrom.conversationLabel} ▾", state.playbackFromOptions,
                                    presenter::selectPlaybackFrom, state.canConfigure) { it.conversationLabel }
                                LanguageMenu("訳す先：${state.selection.playbackTo.label} ▾", state.playbackToOptions,
                                    presenter::selectPlaybackTo, state.canConfigure)
                            }
                        }
                    }
                }
                CutButton(state, level, presenter::cut)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    if (state.selection.source == AudioSource.PLAYBACK) {
                        DesktopOutline(onClick = { presenter.showSubtitle(!state.subtitleVisible) }) {
                            Text(if (state.subtitleVisible) "字幕を隠す" else "字幕を表示")
                        }
                    }
                }
            }
            val error = state.error
            Text(
                error ?: state.notice ?: statusText(state),
                style = MaterialTheme.typography.labelLarge,
                color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 相手の言語の表示。自動で見つけた言語は、誤っていたら選び直せるように「英語（自動）」と出す。 */
private fun partnerLabel(state: AudioState): String = when {
    state.selection.partner != null -> state.selection.partner.conversationLabel
    state.partnerFound && state.partner != null -> "${state.partner.conversationLabel}（自動）"
    else -> "自動"
}

/** 状態の1行（Android 版の会話翻訳の文言にそろえる）。 */
private fun statusText(state: AudioState): String {
    val playback = state.selection.source == AudioSource.PLAYBACK
    val pair = if (playback) {
        "${state.selection.playbackFrom.label} → ${state.selection.playbackTo.label}"
    } else {
        val partner = when {
            state.selection.partner != null -> state.selection.partner.label
            state.partnerFound && state.partner != null -> "${state.partner.label}（自動で判定）"
            else -> "相手の言語は話したら判定"
        }
        "${state.selection.self.label} ⇄ $partner"
    }
    return when {
        !playback && !state.automaticAvailable ->
            "Qwen3-ASR でマイクを使うには Whisper tiny が必要です。設定の「音声認識」からダウンロードしてください。"
        state.phase == AudioPhase.STARTING -> "音声入力を準備中…"
        state.phase == AudioPhase.STOPPING -> "話していた分を確定中…"
        state.canCut -> "聞き取り中…（話し終わると訳が確定します。スペースキーで区切れます）"
        state.listening -> if (playback) "PC の再生音を聞いています（$pair）" else "聞いています（$pair）"
        else -> if (playback) "「開始」を押すと再生音を訳します（$pair）" else "「開始」を押して話してください（$pair）"
    }
}

/**
 * 区切るボタン。押すと、今話している分を確定して次のカードにする（聞くのは続ける）。区切れるときだけ押せ、名前は「区切る」。
 * 区切れないときは押せず、話すよう促す「話して」を出す。周りの輪はマイクの音量で、話し中のカードがある間は色を変える
 * （Android 版の `CutButton`）。
 */
@Composable
private fun CutButton(state: AudioState, level: StateFlow<Float>, onCut: () -> Unit) {
    val raw by level.collectAsState()
    val shown by animateFloatAsState(if (state.listening) raw else 0f, label = "level")
    val c = MaterialTheme.colorScheme
    val ring = if (state.canCut) c.primary else c.outline
    val track = c.surfaceVariant
    Box(Modifier.padding(horizontal = DesktopSize.margin).size(DesktopSize.cutRing), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = 6.dp.toPx()
            val inset = stroke / 2
            val arc = Size(size.width - stroke, size.height - stroke)
            drawArc(track, 0f, 360f, false, Offset(inset, inset), arc, style = Stroke(stroke))
            drawArc(ring, -90f, 360f * shown, false, Offset(inset, inset), arc, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        FilledIconButton(
            onClick = onCut,
            enabled = state.canCut,
            modifier = Modifier.size(DesktopSize.cutButton),
            shape = CircleShape,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = c.primary, contentColor = c.onPrimary),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (state.canCut) "区切る" else "話して", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                if (state.canCut) Text("Space", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}
