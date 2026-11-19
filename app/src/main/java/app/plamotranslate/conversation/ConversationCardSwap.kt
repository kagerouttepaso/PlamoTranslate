package app.plamotranslate.conversation

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.conversation.SwapState
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/*
 * 確定したカードの言語の入れ替え（docs/設計/会話翻訳.md「画面」の「言語の入れ替え」）。カードをもう一方の話し手のカードが
 * 寄る位置へ横にずらす操作と、その間の位置と色の進み具合を持つ。カードの描画（[UtteranceCard]）は、ここが返す進み具合で
 * 色と位置を決めるだけにする。入れ替えられるかは会話の中核が決める（[SwapState]）。
 */

/**
 * 1枚のカードの入れ替えの進み具合。[progress] は 0 が表示している話し手の位置と色、1 がもう一方の話し手の位置と色。
 * [nudge] は古いカードをずらそうとしたときのずれ（px）。[shownRight]・[shownMine] は表示している話し手（寄せる側と色）。
 */
@Stable
internal class CardSwap(right: Boolean, mine: Boolean) {
    val progress = Animatable(0f)
    val nudge = Animatable(0f)
    var shown by mutableStateOf(right to mine)
    val shownRight: Boolean get() = shown.first
    val shownMine: Boolean get() = shown.second

    /** もう一方の話し手の側へ動く向き（px の符号）。 */
    val toward: Float get() = if (shownRight) -1f else 1f

    /** 寄せる位置からのずれ（px）。[gap] はもう一方の話し手のカードが寄る位置までの距離。 */
    fun offsetPx(gap: Float): Float = toward * gap * progress.value + nudge.value
}

/**
 * カード [turnId] の入れ替えの進み具合を覚える。話し手が替わったら（入れ替えた・書き起こし直せずに戻った）、同じフレームで
 * 表示する話し手と進み具合を置き換え、そこから新しい話し手の位置と色へ動かす。入れ替えて届いていた（1）なら、新しい話し手から
 * 見てちょうど 0 なので動かない。ずらしていなければ、前の位置と色から新しい方へ動く。
 */
@Composable
internal fun rememberCardSwap(turnId: Long, right: Boolean, mine: Boolean): CardSwap {
    val swap = remember(turnId) { CardSwap(right, mine) }
    LaunchedEffect(right, mine) {
        if (swap.shown == (right to mine)) return@LaunchedEffect
        swap.progress.snapTo(1f - swap.progress.value)
        swap.shown = right to mine
        swap.progress.animateTo(0f)
    }
    return swap
}

/**
 * カードをずらす操作。入れ替えられるカード（[SwapState.AVAILABLE]）は、もう一方の話し手の側へ指を [SWAP_TRAVEL] 動かすと
 * もう一方の話し手のカードが寄る位置（[gap] 先）に届き、そこで止まって振動する。届いて離すと [onSwap]、届く前に離すと元へ戻る。
 * 古いカード（[SwapState.EXPIRED]）は、少しだけ動いて重く跳ね返る（入れ替えられない手応え）。ほかのカードは動かない。
 * 読み上げを使う人のために、同じ操作をアクセシビリティの操作にも置く。
 */
@Composable
internal fun Modifier.cardSwapGesture(swap: CardSwap, state: SwapState, gap: Float, onSwap: () -> Unit): Modifier {
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    val travel = with(LocalDensity.current) { SWAP_TRAVEL.toPx() }
    val lockedReach = with(LocalDensity.current) { LOCKED_REACH.toPx() }
    var reached by remember(swap) { mutableStateOf(false) }
    return this
        .offset { IntOffset(swap.offsetPx(gap).roundToInt(), 0) }
        .pointerInput(swap, state, swap.shownRight) {
            when (state) {
                SwapState.EXPIRED -> detectHorizontalDragGestures(
                    onDragEnd = { scope.launch { swap.nudge.animateTo(0f, spring(dampingRatio = Spring.DampingRatioMediumBouncy)) } },
                    onDragCancel = { scope.launch { swap.nudge.animateTo(0f) } },
                ) { change, amount ->
                    change.consume()
                    scope.launch { swap.nudge.snapTo((swap.nudge.value + amount * LOCKED_RESISTANCE).coerceIn(-lockedReach, lockedReach)) }
                }
                SwapState.AVAILABLE -> {
                    var moved = 0f
                    detectHorizontalDragGestures(
                        onDragStart = {
                            moved = 0f
                            reached = false
                        },
                        onDragEnd = { if (reached) onSwap() else scope.launch { swap.progress.animateTo(0f) } },
                        onDragCancel = { scope.launch { swap.progress.animateTo(0f) } },
                    ) { change, amount ->
                        change.consume()
                        // もう一方の話し手の側へ動いた分だけ数え、届いたらそこで止める
                        moved = (moved + amount * swap.toward).coerceIn(0f, travel)
                        val nowReached = moved >= travel
                        if (nowReached && !reached) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                        reached = nowReached
                        scope.launch { swap.progress.snapTo(moved / travel) }
                    }
                }
                SwapState.REWRITING, SwapState.UNAVAILABLE -> Unit
            }
        }
        .semantics {
            if (state == SwapState.AVAILABLE) customActions = listOf(CustomAccessibilityAction("言語を入れ替えて書き直す") { onSwap(); true })
        }
}

/**
 * 確定したカードを、もう一方の話し手のカードの位置まで動かすのに要る指の動き。カードの寄せの差（画面の幅の4〜8%）だけでは
 * 縦のスクロールの指のぶれでも届くので、指はこれだけ動かし、カードはその間に寄せの差だけ動く。
 */
private val SWAP_TRAVEL = 72.dp

/** 古いカードをずらそうとしたときに動く上限と、指の動きに対する割合。 */
private val LOCKED_REACH = 6.dp
private const val LOCKED_RESISTANCE = 0.15f
