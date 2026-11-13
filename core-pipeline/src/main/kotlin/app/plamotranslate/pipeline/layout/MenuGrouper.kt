package app.plamotranslate.pipeline.layout

import kotlin.math.abs

/**
 * メニュー・表の項目にまとめる（ゲームのUI・カメラ翻訳の表のモード。作者の指摘「表を読むときは質が下がった」）。1行（1セル）ずつ。
 * ただし、ボタンの中の2段書き（「바람／세기」）は1つにまとめる。1語ずつだと「세기」が「世紀」、2語なら「風の強さ」と訳せた。
 * 行間がほとんどなく（行の高さの0.3倍以内）、中央がそろい、幅が近い2行だけ。表の行どうしはふつう行間が空いている。
 */
object MenuGrouper : LineGrouper {
    override fun group(lines: List<TextLine>): List<TextUnit> {
        val order = lines.indices.sortedBy { lines[it].box.top }
        val used = mutableSetOf<Int>()
        val result = mutableListOf<TextUnit>()
        for (i in order) {
            if (i in used) continue
            val r = lines[i].box
            val pair = order.firstOrNull { j ->
                val o = lines[j].box
                j != i && j !in used && o.top >= r.top &&
                    o.top - r.bottom <= maxOf(r.height, o.height) * 0.3f &&
                    abs(o.centerX - r.centerX) <= maxOf(r.width, o.width) * 0.2f &&
                    maxOf(r.width, o.width) <= minOf(r.width, o.width) * 2.5f
            }
            used += i
            if (pair != null) used += pair
            val members = listOfNotNull(i, pair)
            result += TextUnit(UnitKind.MENU_ITEM, members, unionOf(members.map { lines[it].box }), lines[i].verticalLine && pair == null)
        }
        return result
    }
}
