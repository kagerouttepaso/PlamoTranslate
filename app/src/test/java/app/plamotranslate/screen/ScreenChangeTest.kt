package app.plamotranslate.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class ScreenChangeTest {
    private fun thumb(values: IntArray, cols: Int = values.size) = Thumb(100, 100, cols, values.size / cols, values)

    @Test
    fun 変わったます目の割合を数える() {
        val a = thumb(intArrayOf(10, 10, 10, 10))
        val b = thumb(intArrayOf(10, 200, 15, 10))
        // 5の違いは揺れとして数えない
        assertEquals(0.25f, ScreenChange.changedFraction(a, b), 1e-6f)
    }

    @Test
    fun 覆ったます目は数えない() {
        val a = thumb(intArrayOf(10, 10, 10, 10))
        val b = thumb(intArrayOf(10, 200, 10, 10))
        val mask = booleanArrayOf(false, true, false, false)
        assertEquals(0f, ScreenChange.changedFraction(a, b, mask), 1e-6f)
    }

    @Test
    fun 大きさが違えば全部変わった() {
        val a = Thumb(100, 200, 2, 2, IntArray(4))
        val b = Thumb(200, 100, 2, 2, IntArray(4))
        assertEquals(1f, ScreenChange.changedFraction(a, b), 1e-6f)
    }

    @Test
    fun 中央値で動く背景を消す() {
        // ます目0は止まった文字（200）、ます目1は2回だけ図形が通る背景（20）
        val thumbs = listOf(
            thumb(intArrayOf(200, 20)), thumb(intArrayOf(200, 180)), thumb(intArrayOf(200, 20)),
            thumb(intArrayOf(200, 90)), thumb(intArrayOf(200, 20)),
        )
        assertEquals(listOf(200, 20), ScreenChange.median(thumbs).values.toList())
    }

    @Test
    fun 格子に縮める() {
        val t = ScreenChange.thumb(480, 960) { x, _ -> if (x < 240) 0 else 240 }
        assertEquals(ScreenChange.COLS, t.cols)
        assertEquals(96, t.rows)
        assertEquals(0, t.values[0])
        assertEquals(240, t.values[ScreenChange.COLS - 1])
    }
}
