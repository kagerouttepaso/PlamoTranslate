package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import kotlin.math.max
import kotlin.math.min

/**
 * 読み取った1行（縦書きなら1列）。まとめる工程の入力（docs/設計/画面翻訳.md「まとめる」）。
 * 文字種の判定は読む側（core-ocr）が行い、ここには結果だけを渡す（中核は core-ocr に依存しない）。
 */
data class TextLine(
    val text: String,
    val box: Box,
    /** 仮名を含むか（日本語の行。訳さないので、ほかの行とつながない）。 */
    val kana: Boolean,
    /** ラテン文字だけの行か（狭い吹き出しで縦長になっても横書きとして扱う）。 */
    val latin: Boolean,
) {
    /** 縦書きの列か。英字だけの行は、縦長の枠でも横書きとして扱う（2026-11-06、英語の漫画）。 */
    val verticalLine: Boolean get() = box.isVertical && !latin
}

/** 訳の単位の種類。枠の広げ方を決める（docs/設計/画面翻訳.md「訳文の重ね表示」）。 */
enum class UnitKind { BUBBLE, PARAGRAPH, MENU_ITEM }

/**
 * 訳の単位：まとめた行（[members] は入力の行の番号を読む順に並べたもの）、種類、原文の位置、縦書きか。
 */
data class TextUnit(val kind: UnitKind, val members: List<Int>, val box: Box, val vertical: Boolean)

/** まとめる工程の差し替え口。どの行もちょうど1つの単位に入る（[LayoutContract]）。 */
fun interface LineGrouper {
    fun group(lines: List<TextLine>): List<TextUnit>
}

/** 行のまとめ方。プリセットとメニューで選ぶ。 */
enum class Grouping(val grouper: LineGrouper) {
    /** 吹き出し・段落の塊（漫画）。上下・左右に詰まった行をつなぐ。 */
    BUBBLE(BubbleGrouper),

    /** 文章（チャット・ウェブ）。すぐ下に続く行を段落にし、番号・記号で区切る。 */
    TEXT(TextGrouper),

    /** メニュー・表（ゲームのUI）。1行ずつ。ボタンの中の2段書きだけまとめる。 */
    MENU(MenuGrouper),
}

/** 単位を読み順に並べる：上から、同じ高さ（[ROW_TOLERANCE_PX] 以内）なら左から。 */
object ReadingOrder {
    const val ROW_TOLERANCE_PX = 24

    fun sort(units: List<TextUnit>): List<TextUnit> =
        units.sortedWith(compareBy({ it.box.top / ROW_TOLERANCE_PX }, { it.box.left }))
}

val Box.width: Int get() = right - left
val Box.height: Int get() = bottom - top
val Box.centerX: Int get() = (left + right) / 2
val Box.centerY: Int get() = (top + bottom) / 2
val Box.isVertical: Boolean get() = height >= width * 1.5f && height > 0
val Box.isHorizontal: Boolean get() = width >= height * 1.5f && width > 0

fun Box.union(other: Box) = Box(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))

fun Box.intersects(other: Box) = left < other.right && other.left < right && top < other.bottom && other.top < bottom

fun Box.contains(other: Box) = left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom

/** この枠の面積のうち [other] と重なる割合（0〜1）。 */
fun Box.overlapRatio(other: Box): Float {
    val w = min(right, other.right) - max(left, other.left)
    val h = min(bottom, other.bottom) - max(top, other.top)
    if (w <= 0 || h <= 0) return 0f
    val area = width.toFloat() * height
    return if (area <= 0f) 0f else w.toFloat() * h / area
}

/** どちらかの面積の [ratio] 以上が、もう一方に重なるか。 */
fun Box.overlaps(other: Box, ratio: Float) = overlapRatio(other) >= ratio || other.overlapRatio(this) >= ratio

internal fun unionOf(boxes: List<Box>): Box = boxes.reduce { a, b -> a.union(b) }
