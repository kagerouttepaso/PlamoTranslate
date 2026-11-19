package app.plamotranslate.conversation

import app.plamotranslate.pipeline.conversation.Direction
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.Turn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.launch
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import kotlinx.coroutines.delay
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.plamotranslate.ReadAloud
import app.plamotranslate.llm.Language

/*
 * 会話翻訳のカード（docs/設計/会話翻訳.md「画面」）。対面のカード・1画面のカード・話している最中のカード。
 * 対面は、その面を読む人の言語の文が面を占めるように、見出しとかかった時間を出さず、もう一方の言語を小さくする。
 * 1画面は2人が同じカードの全部を読むので、見出し・原文・訳を同じ重さで出す。
 */

/** 対面で、そのカードを読む人。相手側のカードは逆さに出し、相手の言語を上に出す。 */
internal enum class Reader { ME, PARTNER }

/**
 * 会話のカードの文字の大きさ（2026-11-06、作者の指摘で見直し）。2人が注視して読むものなので、
 * 原文も訳文もしっかり大きくする。特に原文は、話した人が「自分の言葉がどう聞き取られたか」を確かめる大事な情報で、
 * 補足扱いの小さな文字では読めなかった。かかった時間などの内部の情報だけを小さくする。
 */
private object ConversationType {
    /** 読む人の言語で書かれた方（対面）、訳文（1画面）。 */
    val main = TextStyle(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Medium)

    /** 原文（1画面）。 */
    val sub = TextStyle(fontSize = 20.sp, lineHeight = 27.sp)

    /** もう一方の言語（対面）。読む人には読めない言語なので、話した人が聞き取りを確かめられる程度に小さくする。 */
    val faceSub = TextStyle(fontSize = 14.sp, lineHeight = 19.sp)
}

/**
 * 誰の発話かで色を決める。対面の上下どちらの半分でも、同じ人の発話は同じ色にする。
 * 自分は primary（夜の鳥はコーラル、さえずりの紙は朱）、相手は secondary（ミント・緑）の面（docs/設計/デザイン言語.md「色」）。
 */
@Composable
private fun speakerColors(mine: Boolean): Pair<Color, Color> {
    val c = MaterialTheme.colorScheme
    return if (mine) c.primaryContainer to c.onPrimaryContainer else c.secondaryContainer to c.onSecondaryContainer
}

/** 1画面のカードの見出し（誰が・何語から何語へ）。 */
private fun cardLabel(direction: Direction): String =
    "${if (direction.mine) "自分" else "相手"}・${direction.source.label} → ${direction.target?.label ?: "（相手の言語を判定中）"}"


/** 対面で、その側の表示を読む人の言語。自分側は自分が話した・訳された言語、相手側は相手のもの。 */
private fun readerLanguage(d: Direction, reader: Reader): Language =
    if (d.mine == (reader == Reader.ME)) d.source else d.target ?: d.source

/**
 * 対面のカード。読む人の言語で書かれた方を上に大きく、もう一方をその下に小さく出す。
 * 誰が何語から何語へ話したかは、色と左右と読む人の言語で分かるので見出しを出さない（読み上げ中の🔊だけ出す）。
 */
@Composable
internal fun FaceCard(turn: Turn, reader: Reader, onSwap: (Long) -> Unit) {
    val d = turn.direction
    val readerLanguage = readerLanguage(d, reader)
    val originalFirst = d.source == readerLanguage
    val translation = turn.shownTranslation.ifEmpty { "…" }
    UtteranceCard(
        turn = turn,
        translationIsMain = !originalFirst,
        label = null,
        mine = d.mine,
        right = d.mine == (reader == Reader.ME),
        main = if (originalFirst) turn.original else translation,
        mainPending = !originalFirst && !turn.translated,
        sub = if (originalFirst) translation else turn.original,
        subPending = originalFirst && !turn.translated,
        stats = "",
        subStyle = ConversationType.faceSub,
        compact = true,
        onSwap = onSwap,
    )
}

/** 1画面のカード。原文の下に訳を大きく出す。 */
@Composable
internal fun SharedCard(turn: Turn, onSwap: (Long) -> Unit) {
    UtteranceCard(
        turn = turn,
        translationIsMain = true,
        label = cardLabel(turn.direction),
        mine = turn.direction.mine,
        right = turn.direction.mine,
        main = turn.shownTranslation.ifEmpty { "…" },
        mainPending = !turn.translated,
        sub = turn.original,
        subPending = false,
        stats = turn.statsText,
        subFirst = true,
        onSwap = onSwap,
    )
}

/**
 * 確定したカード。見出し（誰が・何語から何語へ。[label] が null なら読み上げ中の🔊だけ）、本文2つ、かかった時間を縦に並べる。
 * [compact] は対面のカードで、余白を詰めて本文に面を譲る。
 * 色は話した人ごとに分け、背景と混ざらないよう地の色との差がはっきりした組み合わせにする。
 * 訳文をタップすると、訳した先の言語で読み上げる（2026-11-08、作者の要望。[ReadAloud]）。読んでいる間は見出しに🔊を出し、
 * もう一度タップすると止める。
 *
 * 言語の入れ替え（[onSwap]。もう一方の話し手の言語として書き起こし直し、訳し直す）は、カードをもう一方の話し手のカードの
 * 位置へ横にずらして行う（2026-11-19、作者の要望）。動かせるかは会話の中核が決める（[Turn.swappable]）。入れ替えられる
 * カードはふつうのまま見せ、もう入れ替えられない古いカード（音声を手放したもの）は、色を地の色へ少し寄せて落ち着かせ、
 * ずらそうとしても少し動いて跳ね返る（入れ替えられるカードに記号やハンドルを足すと主張が強かった）。
 * - カードはもう一方の話し手のカードの位置までしか動かず、そこで止まる（行き過ぎない）。指を [SWAP_TRAVEL] 動かすと届き、
 *   届いた時に端末を短く振動させる。動かすにつれて、色がもう一方の話し手の色へ移る。
 * - 届いて離したら入れ替える。会話の中核はその場で話し手を替えるので、カードは新しい話し手の位置と色のまま残り、
 *   原文は書き起こし直しが届くまで薄く出し、訳は届いた順に出る。届く前に離したら元の位置と色へ戻る。
 * - 位置と色は、指の動き・入れ替え・書き起こし直せずに戻ったときのどれでも、跳ばずにつながって動く。
 * 向きは、もう一方の話し手のカードのある側だけ（対面の相手側は逆さに出すが、カードの中の向きで数えるので同じ）。
 * 読み上げを使う人のために、同じ操作をアクセシビリティの操作にも置く。
 */
@Composable
private fun UtteranceCard(
    turn: Turn,
    translationIsMain: Boolean,
    label: String?,
    mine: Boolean,
    right: Boolean,
    main: String,
    mainPending: Boolean,
    sub: String,
    subPending: Boolean,
    stats: String,
    subFirst: Boolean = false,
    subStyle: TextStyle = ConversationType.sub,
    compact: Boolean = false,
    onSwap: (Long) -> Unit,
) {
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val key = "turn-${turn.id}"
    val speak = Modifier.clickable(enabled = turn.translated && turn.failure == null && turn.translation.isNotBlank()) {
        turn.direction.target?.let { ReadAloud.toggle(context, key, turn.translation, it) }
    }
    val swappable = turn.swappable
    // 書き起こし直しを待っている間は、原文がまだ前の言語のもの
    val rewriting = turn.swappedFrom != null
    val widthFraction = if (compact) 0.96f else 0.92f
    val scope = rememberCoroutineScope()
    val travel = with(LocalDensity.current) { SWAP_TRAVEL.toPx() }
    val lockedReach = with(LocalDensity.current) { LOCKED_REACH.toPx() }
    // 音声を手放した古いカード（もう入れ替えられない）。相手がまだ決まっていない間の自分のカードは古くないので含めない
    val locked = turn.utteranceId != null && !turn.retained
    val nudge = remember(turn.id) { Animatable(0f) }

    // 入れ替えの進み具合（0：表示している話し手の位置と色、1：もう一方の話し手の位置と色）
    val progress = remember(turn.id) { Animatable(0f) }
    // 表示している話し手（寄せる側と色）。話し手が替わったら（入れ替えた・書き起こし直せずに戻った）、同じフレームで
    // 表示する話し手と進み具合を置き換え、そこから新しい話し手の位置と色へ動かす。入れ替えて届いていた（1）なら、
    // 新しい話し手から見てちょうど 0 なので動かない。ずらしていなければ、前の位置と色から新しい方へ動く
    var shown by remember(turn.id) { mutableStateOf(right to mine) }
    LaunchedEffect(right, mine) {
        if (shown == (right to mine)) return@LaunchedEffect
        progress.snapTo(1f - progress.value)
        shown = right to mine
        progress.animateTo(0f)
    }
    val (shownRight, shownMine) = shown
    val (ownContainer, ownContent) = speakerColors(shownMine)
    val (otherContainer, otherContent) = speakerColors(!shownMine)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // もう一方の話し手のカードの位置までの距離（左右の寄せの差）
        val gap = constraints.maxWidth * (1f - widthFraction)
        val toward = if (shownRight) -1f else 1f
        val p = progress.value
        // 古いカード（音声を手放し、もう入れ替えられない）は、色を地の色へ少し寄せて落ち着かせる。手放したときにじわっと変わる
        val settled by animateFloatAsState(if (locked) 1f else 0f, tween(SETTLE_MILLIS), label = "settled")
        val container = lerp(lerp(ownContainer, otherContainer, p), MaterialTheme.colorScheme.surface, SETTLED_MIX * settled)
        val content = lerp(ownContent, otherContent, p)
        val pendingColor = content.copy(alpha = 0.45f)
        var reached by remember(turn.id) { mutableStateOf(false) }
        val swap = Modifier
            .align(if (shownRight) Alignment.TopEnd else Alignment.TopStart)
            .offset { IntOffset((toward * gap * p + nudge.value).roundToInt(), 0) }
            .pointerInput(turn.id, swappable, locked, shownRight) {
                if (locked) {
                    // 古いカードは、ずらそうとしても少しだけ動いて重く跳ね返る（入れ替えられない手応え）
                    detectHorizontalDragGestures(
                        onDragEnd = { scope.launch { nudge.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) } },
                        onDragCancel = { scope.launch { nudge.animateTo(0f) } },
                    ) { change, amount ->
                        change.consume()
                        scope.launch { nudge.snapTo((nudge.value + amount * LOCKED_RESISTANCE).coerceIn(-lockedReach, lockedReach)) }
                    }
                    return@pointerInput
                }
                if (!swappable) return@pointerInput
                var moved = 0f
                detectHorizontalDragGestures(
                    onDragStart = {
                        moved = 0f
                        reached = false
                    },
                    onDragEnd = {
                        if (reached) {
                            onSwap(turn.id)
                        } else {
                            scope.launch { progress.animateTo(0f) }
                        }
                    },
                    onDragCancel = { scope.launch { progress.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    // もう一方の話し手の側へ動いた分だけ数え、届いたらそこで止める
                    moved = (moved + amount * toward).coerceIn(0f, travel)
                    val nowReached = moved >= travel
                    if (nowReached && !reached) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    reached = nowReached
                    scope.launch { progress.snapTo(moved / travel) }
                }
            }
            .semantics {
                if (swappable) customActions = listOf(CustomAccessibilityAction("言語を入れ替えて書き直す") { onSwap(turn.id); true })
            }
        Surface(
            modifier = swap.fillMaxWidth(widthFraction),
            shape = RoundedCornerShape(16.dp),
            color = container,
            contentColor = content,
        ) {
            Box {
                SelectionContainer {
                    Column(
                        Modifier
                            .animateContentSize()
                            .padding(horizontal = if (compact) 12.dp else 14.dp, vertical = if (compact) 8.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(if (compact) 3.dp else 6.dp),
                    ) {
                        val heading = when {
                            label == null -> if (speaking == key) "🔊" else null
                            speaking == key -> "🔊 $label"
                            else -> label
                        }
                        heading?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = content.copy(alpha = 0.7f)) }
                        val mainText = @Composable {
                            Text(
                                main,
                                style = ConversationType.main,
                                color = if (mainPending || (rewriting && !translationIsMain)) pendingColor else content,
                                modifier = if (translationIsMain) speak else Modifier,
                            )
                        }
                        val subText = @Composable {
                            if (sub.isNotEmpty()) {
                                Text(
                                    sub,
                                    style = subStyle,
                                    color = if (subPending || (rewriting && translationIsMain)) pendingColor else content.copy(alpha = 0.85f),
                                    modifier = if (translationIsMain) Modifier else speak,
                                )
                            }
                        }
                        if (subFirst) { subText(); mainText() } else { mainText(); subText() }
                        if (stats.isNotEmpty()) {
                            Text(stats, style = MaterialTheme.typography.labelSmall, color = content.copy(alpha = 0.55f))
                        }
                    }
                }
            }
        }
    }
}

/**
 * 話している最中の聞き取りと途中訳。2人が注視して読むものなので、確定したカードと同じ大きさ・濃さで出し、
 * 枠線で囲って「話し中（まだ変わる）」ことを示す。1画面は見出しも出し、対面は確定したカードと同じく見出しを出さず
 * もう一方の言語を小さくする。話し終わると確定したカードに置き換わる。
 *
 * 書き起こしの重いモデルは文字が出るまで数秒かかるので、待っている間も「聞いている」ことが両方の面から分かるようにする
 * （2026-11-15、作者の試用）。カードの下に音量のバー（[level]）を出し、文字がまだ無い間は話し始めからの秒数を出す。
 * 音声認識が録音から1秒以上遅れていれば（[lagSeconds]）、その秒数も出す。言語が先に分かれば、文字より先にカードの向きが決まる。
 *
 * 見出しに、誰が何語で話しているかを出し、枠・見出し・音量のバーを話した人の色（確定したカードと同じ）にする。
 * 話し手が代わると色が変わるので、カードが替わったことがひと目で分かる（2026-11-15、作者の要望）。話し手が分かる前は中立の色。
 * 相手側は相手が読むので、言語をその言語自身の名前で出す（例：한국어）。
 */
@Composable
internal fun LiveCard(live: LiveSpeech, reader: Reader?, level: Float, lagSeconds: Float, queued: Boolean = false) {
    val d = live.direction
    val c = MaterialTheme.colorScheme
    val right = when {
        d == null -> reader != Reader.PARTNER
        reader == null -> d.mine
        else -> d.mine == (reader == Reader.ME)
    }
    val readerLanguage = if (d == null || reader == null) null else readerLanguage(d, reader)
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
    // 相手側は相手が読むので、言語に依らない書き方にする
    val waiting = if (reader == Reader.PARTNER) "… ${seconds}s" else "書き起こし中… ${seconds}秒"
    val original = live.text.ifEmpty { waiting }
    val translation = live.translation.ifEmpty { "…" }
    val lag = if (!queued && lagSeconds >= 1f && reader != Reader.PARTNER) "認識が%.0f秒遅れています".format(lagSeconds) else null
    val shownLevel by animateFloatAsState(level, label = "liveLevel")
    val accent = when {
        d == null -> c.outline
        d.mine -> c.primary
        else -> c.secondary
    }
    val status = if (queued) queuedStatus(d, reader) else liveStatus(d, reader)
    val levelBar = @Composable {
        Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.surfaceVariant)) {
            Box(Modifier.fillMaxWidth(shownLevel).fillMaxHeight().background(accent))
        }
    }
    // 1画面は確定したカードと同じく「原文の下に訳」。対面は読む人の言語を上に
    val originalFirst = reader == null || d == null || d.source == readerLanguage
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
        Surface(
            modifier = Modifier.fillMaxWidth(if (reader == null) 0.92f else 0.96f),
            shape = RoundedCornerShape(16.dp),
            color = c.surfaceContainerHigh,
            contentColor = c.onSurface,
            border = BorderStroke(if (queued) 1.dp else 2.dp, if (queued) accent.copy(alpha = 0.6f) else accent),
        ) {
            if (reader == null) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(status, style = MaterialTheme.typography.labelLarge, color = accent)
                    Text(original, style = ConversationType.sub)
                    Text(translation, style = ConversationType.main)
                    lag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                    if (!queued) levelBar()
                }
            } else {
                Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(status, style = MaterialTheme.typography.labelMedium, color = accent)
                    Text(if (originalFirst) original else translation, style = ConversationType.main)
                    Text(if (originalFirst) translation else original, style = ConversationType.faceSub, color = c.onSurfaceVariant)
                    lag?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant) }
                    if (!queued) levelBar()
                }
            }
        }
    }
}

/**
 * 区切るボタンで区切ったが、まだ確定していないカード（書き起こし待ち）。話し中のカードと同じ形で、枠を細く薄くし、
 * 見出しを「書き起こし中」にする。押した時にカードが区切られたことを見せ、書き起こしが追いつくと確定したカードに置き換わる。
 */
@Composable
internal fun QueuedCard(card: LiveSpeech, reader: Reader?) = LiveCard(card, reader, level = 0f, lagSeconds = 0f, queued = true)

/** 書き起こし待ちのカードの見出し。相手側は言語に依らない記号（⏳）にする。 */
private fun queuedStatus(d: Direction?, reader: Reader?): String = when {
    reader == Reader.PARTNER -> if (d == null) "⏳" else "⏳ ${ownName(d.source)}"
    d == null -> "⏳ 区切りました・書き起こし中"
    else -> "⏳ ${if (d.mine) "自分" else "相手"}・${d.source.label}・書き起こし中"
}

/**
 * 話し中のカードの見出し：誰が何語で話しているか。自分側と1画面は「● 相手・韓国語」、相手側は相手が読めるよう
 * 言語をその言語自身の名前で出す（「● 한국어」）。話し手がまだ分からなければ「● 話し中」（相手側は「●」）。
 */
private fun liveStatus(d: Direction?, reader: Reader?): String = when {
    d == null -> if (reader == Reader.PARTNER) "●" else "● 話し中"
    reader == Reader.PARTNER -> "● ${ownName(d.source)}"
    else -> "● ${if (d.mine) "自分" else "相手"}・${d.source.label}"
}

/** 言語の、その言語自身での名前（例：韓国語は「한국어」）。端末が名前を持たなければ言語のコード。 */
private fun ownName(language: Language): String {
    val locale = java.util.Locale.forLanguageTag(language.code)
    return locale.getDisplayName(locale).ifBlank { language.code }
        .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }
}

/**
 * 確定したカードを、もう一方の話し手のカードの位置まで動かすのに要る指の動き。カードの寄せの差（画面の幅の4〜8%）だけでは
 * 縦のスクロールの指のぶれでも届くので、指はこれだけ動かし、カードはその間に寄せの差だけ動く。
 */
private val SWAP_TRAVEL = 72.dp

/**
 * 古いカード（音声を手放し、もう入れ替えられない）の見せ方。入れ替えられるカードに記号やハンドルを足すと主張が強かった
 * （2026-11-19、作者の指摘）ので、入れ替えられるカードはふつうのまま、古いカードの側を落ち着かせる。色は地の色へ寄せる割合、
 * ずらそうとしたときに動く上限と、指の動きに対する割合。文字の濃さは変えない（2人が読むので読みやすさを落とさない）。
 */
private const val SETTLED_MIX = 0.35f
private const val SETTLE_MILLIS = 600
private val LOCKED_REACH = 6.dp
private const val LOCKED_RESISTANCE = 0.15f
