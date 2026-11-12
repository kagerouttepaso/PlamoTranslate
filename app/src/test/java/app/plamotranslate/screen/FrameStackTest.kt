package app.plamotranslate.screen

import org.junit.Assert.assertEquals
import org.junit.Test

class FrameStackTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    @Test
    fun 止まっている文字は残り半分未満のコマの動きは消える() {
        val text = rgb(255, 255, 255)
        val background = rgb(20, 30, 40)
        val character = rgb(200, 50, 50)
        // 画素0は文字（5枚とも白）。画素1は背景で、2枚だけキャラクターが通る
        val frames = listOf(
            intArrayOf(text, background),
            intArrayOf(text, character),
            intArrayOf(text, background),
            intArrayOf(text, character),
            intArrayOf(text, background),
        )
        val out = IntArray(2)
        FrameStack.medianInto(frames, out, 0, 2)
        assertEquals(text, out[0])
        assertEquals(background, out[1])
    }

    @Test
    fun 色の成分ごとに中央値を取る() {
        val frames = listOf(
            intArrayOf(rgb(10, 200, 0)),
            intArrayOf(rgb(30, 100, 255)),
            intArrayOf(rgb(20, 150, 128)),
        )
        val out = IntArray(1)
        FrameStack.medianInto(frames, out, 0, 1)
        assertEquals(rgb(20, 150, 128), out[0])
    }

    @Test
    fun 偶数枚なら小さい方の中央値() {
        val frames = listOf(intArrayOf(rgb(0, 0, 0)), intArrayOf(rgb(100, 100, 100)), intArrayOf(rgb(50, 50, 50)), intArrayOf(rgb(200, 200, 200)))
        val out = IntArray(1)
        FrameStack.medianInto(frames, out, 0, 1)
        assertEquals(rgb(50, 50, 50), out[0])
    }
}
