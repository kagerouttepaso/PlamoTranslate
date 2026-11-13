package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 吹き出し・段落の塊にまとめる（漫画）。読んだ行を、位置の近さで束ねる。
 *
 * - 行の形を横長（幅が高さの1.5倍以上）・縦長（高さが幅の1.5倍以上）・正方形に近いものに分ける。
 *   1〜2文字の短い列は正方形に近くなるので、縦長・横長のどちらともつなげる。
 * - 横書きの行は、上下に詰まって並び（行間が行の高さの0.8倍未満）、左右が重なるものを同じまとまりにする。
 * - 縦書きの列は、左右に詰まって並び（列間が列の幅の0.8倍未満）、上下が重なるものを同じまとまりにする。
 * - 範囲が大きく重なる行は、形に関係なく同じまとまりにする。重なったまま別々に訳を描くと読めなくなる
 *   （2026-11-05、作者の指摘）。
 * - 仮名を含む行（日本語）とそれ以外の行はつながない。日本語の行は訳さないため、混ぜると訳す範囲がずれる。
 * - 縦書きのまとまりは右の列から、横書きは上の行から読む。
 */
object BubbleGrouper : LineGrouper {
    private const val GAP_RATIO = 0.8f

    override fun group(lines: List<TextLine>): List<TextUnit> {
        val parent = IntArray(lines.size) { it }
        fun find(i: Int): Int {
            var x = i
            while (parent[x] != x) { parent[x] = parent[parent[x]]; x = parent[x] }
            return x
        }
        for (i in lines.indices) {
            for (j in i + 1 until lines.size) {
                if (connected(lines[i], lines[j])) parent[find(i)] = find(j)
            }
        }
        return lines.indices.groupBy(::find).values.map { members -> unit(lines, members) }
    }

    private fun unit(lines: List<TextLine>, members: List<Int>): TextUnit {
        val vertical = members.any { lines[it].verticalLine } && members.none { lines[it].box.isHorizontal }
        val ordered = if (vertical) members.sortedByDescending { lines[it].box.right } else rowOrder(lines, members)
        return TextUnit(UnitKind.BUBBLE, ordered, unionOf(members.map { lines[it].box }), vertical)
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

    private fun connected(a: TextLine, b: TextLine): Boolean {
        if (a.kana != b.kana) return false
        val ra = a.box
        val rb = b.box
        if (ra.overlaps(rb, 0.2f)) return true
        val latin = a.latin || b.latin
        val rows = !a.verticalLine && !b.verticalLine
        val columns = !latin && !ra.isHorizontal && !rb.isHorizontal
        return (rows && rowsConnected(ra, rb)) || (columns && columnsConnected(ra, rb))
    }

    private fun rowsConnected(ra: Box, rb: Box): Boolean {
        val gap = max(ra.top, rb.top) - min(ra.bottom, rb.bottom)
        val lineHeight = min(ra.height, rb.height)
        val overlapX = min(ra.right, rb.right) - max(ra.left, rb.left)
        // 中央そろえの吹き出しは行の幅が違うので、中心の近さでも同じ列とみなす
        val centerNear = abs(ra.centerX - rb.centerX) < max(ra.width, rb.width) / 2
        return gap < lineHeight * GAP_RATIO && (overlapX > 0 || centerNear)
    }

    private fun columnsConnected(ra: Box, rb: Box): Boolean {
        val gap = max(ra.left, rb.left) - min(ra.right, rb.right)
        val columnWidth = min(ra.width, rb.width)
        val overlapY = min(ra.bottom, rb.bottom) - max(ra.top, rb.top)
        return gap < columnWidth * GAP_RATIO && overlapY > 0
    }
}
