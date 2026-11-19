package app.plamotranslate.conversation

import app.plamotranslate.pipeline.conversation.Direction
import app.plamotranslate.pipeline.conversation.LiveSpeech
import app.plamotranslate.pipeline.conversation.SwapState
import app.plamotranslate.pipeline.conversation.Turn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.lerp
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
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
 * 言語の入れ替え（[onSwap]）は、カードをもう一方の話し手のカードの位置へ横にずらして行う。操作と、その間の位置と色の
 * 進み具合は [cardSwapGesture]・[rememberCardSwap] が持ち、ここはその進み具合で位置と色を決める。もう入れ替えられない古いカード
 * （[SwapState.EXPIRED]）は、色を背景色へ少し寄せて落ち着かせる。書き起こし直しを待っている間（[SwapState.REWRITING]）は、
 * 原文を薄く出す。
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
    val speaking by ReadAloud.speaking.collectAsStateWithLifecycle()
    val key = "turn-${turn.id}"
    val speak = Modifier.clickable(enabled = turn.translated && turn.failure == null && turn.translation.isNotBlank()) {
        turn.direction.target?.let { ReadAloud.toggle(context, key, turn.translation, it) }
    }
    // 書き起こし直しを待っている間は、原文がまだ前の言語のもの
    val rewriting = turn.swap == SwapState.REWRITING
    val widthFraction = if (compact) 0.96f else 0.92f
    val swap = rememberCardSwap(turn.id, right, mine)
    val (ownContainer, ownContent) = speakerColors(swap.shownMine)
    val (otherContainer, otherContent) = speakerColors(!swap.shownMine)

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // もう一方の話し手のカードの位置までの距離（左右の寄せの差）
        val gap = constraints.maxWidth * (1f - widthFraction)
        val p = swap.progress.value
        // 古いカードは、色を背景色へ少し寄せて落ち着かせる。音声を破棄したときにじわっと変わる
        val settled by animateFloatAsState(if (turn.swap == SwapState.EXPIRED) 1f else 0f, tween(SETTLE_MILLIS), label = "settled")
        val container = lerp(lerp(ownContainer, otherContainer, p), MaterialTheme.colorScheme.surface, SETTLED_MIX * settled)
        val content = lerp(ownContent, otherContent, p)
        val pendingColor = content.copy(alpha = 0.45f)
        val placed = Modifier
            .align(if (swap.shownRight) Alignment.TopEnd else Alignment.TopStart)
            .cardSwapGesture(swap, turn.swap, gap) { onSwap(turn.id) }
        Surface(
            modifier = placed.fillMaxWidth(widthFraction),
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
 * 古いカード（音声を破棄し、もう入れ替えられない）の色。入れ替えられるカードに記号やハンドルを足すと主張が強かった
 * （2026-11-19、作者の指摘）ので、入れ替えられるカードはふつうのまま、古いカードの側を落ち着かせる。背景色へ寄せる割合と、
 * 移る時間。文字の色は変えない（2人が読むので読みやすさを落とさない）。
 */
private const val SETTLED_MIX = 0.35f
private const val SETTLE_MILLIS = 600
