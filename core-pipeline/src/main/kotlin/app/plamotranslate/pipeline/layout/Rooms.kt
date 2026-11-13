package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import kotlin.math.max
import kotlin.math.min

/**
 * 訳の単位ごとの「占めてよい範囲」（docs/設計/画面翻訳.md「占めてよい範囲」）。枠を広げるときは、この範囲を出ない。
 * まとめ方によらず、ここの1か所で決める。
 *
 * 範囲は画面全体から始め、2つの単位ごとに、原文の間の隙間の真ん中で分ける（隙間の広い向きで分ける）。
 * 約束（`RoomsTest`）：範囲は自分の原文の位置を含み、画面を出ず、原文が重ならない2つの単位の範囲は重ならない。
 * 原文どうしが重なる単位は分けられないので、範囲も重なりうる。
 */
object Rooms {
    fun assign(boxes: List<Box>, screen: Box): List<Box> {
        val rooms = boxes.map { screen.union(it) }.toMutableList()
        for (i in boxes.indices) {
            for (j in i + 1 until boxes.size) {
                if (!rooms[i].intersects(rooms[j])) continue
                val a = boxes[i]
                val b = boxes[j]
                val gapX = max(a.left, b.left) - min(a.right, b.right)
                val gapY = max(a.top, b.top) - min(a.bottom, b.bottom)
                if (gapX < 0 && gapY < 0) continue
                if (gapX >= gapY) {
                    val (l, r) = if (a.right <= b.left) i to j else j to i
                    val mid = (boxes[l].right + boxes[r].left) / 2
                    rooms[l] = rooms[l].copy(right = min(rooms[l].right, mid))
                    rooms[r] = rooms[r].copy(left = max(rooms[r].left, mid))
                } else {
                    val (t, u) = if (a.bottom <= b.top) i to j else j to i
                    val mid = (boxes[t].bottom + boxes[u].top) / 2
                    rooms[t] = rooms[t].copy(bottom = min(rooms[t].bottom, mid))
                    rooms[u] = rooms[u].copy(top = max(rooms[u].top, mid))
                }
            }
        }
        return rooms
    }

    /**
     * 広げた枠 [grown] を、範囲 [room] に収める。原文の位置 [box] は必ず含める（範囲は原文を含むので、収めても原文は欠けない）。
     */
    fun clip(grown: Box, room: Box, box: Box): Box = Box(
        max(grown.left, room.left), max(grown.top, room.top),
        min(grown.right, room.right), min(grown.bottom, room.bottom),
    ).union(box)
}
