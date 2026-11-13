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

    @Test
    fun 余白を足した枠も範囲に収める() {
        // 隙間の狭いメニューの項目：余白（左右4・上下2）を足すと隣の範囲へ出るので、範囲で切る
        val boxes = listOf(Box(100, 100, 200, 140), Box(204, 100, 300, 140))
        val rooms = Rooms.assign(boxes, screen)
        val drawn = Rooms.drawn(boxes[0], grown = null, padX = 4, padY = 2, room = rooms[0])
        assertEquals(Box(96, 98, 202, 142), drawn)
        assertFalse(drawn.intersects(rooms[1]))
    }

    @Test
    fun 範囲が無ければ余白をそのまま足す() {
        assertEquals(Box(96, 98, 204, 142), Rooms.drawn(Box(100, 100, 200, 140), null, 4, 2, room = null))
    }

    @Test
    fun 広げた枠には余白を足さない() {
        val grown = Box(50, 50, 260, 200)
        assertEquals(grown, Rooms.drawn(Box(100, 100, 200, 140), grown, 4, 2, room = Box(0, 0, 300, 300)))
    }
}
