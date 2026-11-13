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

/** 訳の単位の種類。[expandable] なら、「広げる」を選んだとき占めてよい範囲の中で背景いっぱいまで広げる（docs/設計/画面翻訳.md「訳文の重ね表示」）。 */
enum class UnitKind(val expandable: Boolean) {
    /** 吹き出し・段落の塊（漫画）。 */
    BUBBLE(true),

    /** 文章の段落。広げると隣の段落や名前にかかりやすいので広げない。 */
    PARAGRAPH(false),

    /** メニュー・ボタンの項目。項目の背景いっぱいまで広げる。 */
    MENU_ITEM(true),

    /** まとめる前の1行（カメラ翻訳は行を追ってからまとめる）。 */
    LINE(false),
}

/**
 * 訳の単位：まとめた行（[members] は入力の行の番号を読む順に並べたもの）、種類、原文の位置、縦書きか。
 * 位置・縦書きか・読む順は、まとめ方によらず [TextLayout.unitOf] の1か所で決める。
 */
data class TextUnit(val kind: UnitKind, val members: List<Int>, val box: Box, val vertical: Boolean)

/** まとめる工程の差し替え口：どの行を1つの単位にするかだけを返す。どの行もちょうど1つの組に入る（`GroupersTest`）。 */
fun interface LineGrouper {
    fun group(lines: List<TextLine>): List<List<Int>>
}

/** 行のまとめ方。プリセットとメニューで選ぶ。[kind] はできる単位の種類。 */
enum class Grouping(val grouper: LineGrouper, val kind: UnitKind) {
    /** 吹き出し・段落の塊（漫画）。上下・左右に詰まった行をつなぐ。 */
    BUBBLE(BubbleGrouper, UnitKind.BUBBLE),

    /** 文章（チャット・ウェブ）。すぐ下に続く行を段落にし、番号・記号で区切る。 */
    TEXT(TextGrouper, UnitKind.PARAGRAPH),

    /** メニュー・表（ゲームのUI）。1行ずつ。ボタンの中の2段書きだけまとめる。 */
    MENU(MenuGrouper, UnitKind.MENU_ITEM),
}

/** 単位と、その占めてよい範囲（[Rooms]）。 */
data class PlacedUnit(val unit: TextUnit, val room: Box)

/** まとめる工程の全体：組を作り、単位にし、読み順に並べ、占めてよい範囲を付ける。 */
object TextLayout {
    /**
     * [lines] を [grouping] でまとめ、読み順に並べて範囲を付ける（画面翻訳）。[screen] は画面の範囲。
     * 約束（`GroupersTest`）：どの行もちょうど1つの単位に入る。範囲は原文を含み画面を出ず、原文の重ならない単位どうしで重ならない。
     */
    fun arrange(lines: List<TextLine>, grouping: Grouping, screen: Box): List<PlacedUnit> {
        val units = ReadingOrder.sort(units(lines, grouping))
        val rooms = Rooms.assign(units.map { it.box }, screen)
        return units.zip(rooms) { u, r -> PlacedUnit(u, r) }
    }

    /** [lines] を [grouping] でまとめた単位（並べ替えず、範囲も付けない。カメラ翻訳の段落）。 */
    fun units(lines: List<TextLine>, grouping: Grouping): List<TextUnit> =
        grouping.grouper.group(lines).map { unitOf(grouping.kind, it, lines) }

    /**
     * 行の組を単位にする。縦の列だけ（横長の行を含まない）なら縦書きで右の列から、そうでなければ上の行から読む。
     * 位置は入った行の位置を合わせたもの。
     */
    fun unitOf(kind: UnitKind, members: List<Int>, lines: List<TextLine>): TextUnit {
        val vertical = members.any { lines[it].verticalLine } && members.none { lines[it].box.isHorizontal }
        val ordered = if (vertical) members.sortedByDescending { lines[it].box.right } else rowOrder(lines, members)
        return TextUnit(kind, ordered, unionOf(members.map { lines[it].box }), vertical)
    }

    /**
     * 横書きの行を上から、同じ高さの行は左から並べる。上端の数ピクセルの差で左右が逆転しないよう、
     * 行の高さの半分以上重なる行を同じ段とみなす（2026-11-06、英語の吹き出しで「HAVE FUN / I STILL」と逆転した）。
     */
    private fun rowOrder(lines: List<TextLine>, members: List<Int>): List<Int> {
        val rows = mutableListOf<MutableList<Int>>()
        for (i in members.sortedBy { lines[it].box.centerY }) {
            val row = rows.lastOrNull()
            val ref = row?.first()?.let { lines[it].box }
            val box = lines[i].box
            val overlap = if (ref == null) 0 else min(ref.bottom, box.bottom) - max(ref.top, box.top)
            if (ref != null && overlap > min(ref.height, box.height) / 2) row.add(i) else rows += mutableListOf(i)
        }
        return rows.flatMap { row -> row.sortedBy { lines[it].box.left } }
    }
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
