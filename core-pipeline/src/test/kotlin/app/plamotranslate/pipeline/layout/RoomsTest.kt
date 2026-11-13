package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 占めてよい範囲（[Rooms]）の約束。 */
class RoomsTest {
    private val screen = Box(0, 0, 1080, 2400)

    @Test
    fun 範囲は原文を含み_画面を出ず_原文の重ならない単位どうしは重ならない() {
        val random = Random(7)
        repeat(200) {
            val boxes = List(random.nextInt(1, 15)) {
                val l = random.nextInt(0, 1000)
                val t = random.nextInt(0, 2300)
                Box(l, t, minOf(1080, l + random.nextInt(5, 300)), minOf(2400, t + random.nextInt(5, 120)))
            }
            val rooms = Rooms.assign(boxes, screen)
            for (i in boxes.indices) {
                assertTrue(rooms[i].contains(boxes[i]))
                assertTrue(screen.contains(rooms[i]))
                for (j in boxes.indices) {
                    if (i != j && !boxes[i].intersects(boxes[j])) {
                        assertFalse("$i ${boxes[i]} ${rooms[i]} / $j ${boxes[j]} ${rooms[j]}", rooms[i].intersects(rooms[j]))
                    }
                }
            }
        }
    }

    @Test
    fun 隣のメニューの項目との境目は真ん中() {
        val rooms = Rooms.assign(listOf(Box(100, 100, 200, 140), Box(300, 100, 400, 140)), screen)
        assertEquals(250, rooms[0].right)
        assertEquals(250, rooms[1].left)
    }

    @Test
    fun 広げた枠は範囲に収め_原文は欠けない() {
        val box = Box(100, 100, 200, 140)
        val room = Box(0, 0, 250, 300)
        assertEquals(Box(50, 60, 250, 180), Rooms.clip(Box(50, 60, 400, 180), room, box))
    }
}
