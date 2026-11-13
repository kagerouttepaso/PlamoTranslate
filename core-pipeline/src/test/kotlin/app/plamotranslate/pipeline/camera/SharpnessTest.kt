package app.plamotranslate.pipeline.camera

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1行の画像の鋭さ（[Sharpness]）。 */
class SharpnessTest {
    /** 白地に黒の縦縞（幅 [period] の半分ずつ）。[blur] 画素の横の平均でぼかし、[contrast] で濃さを変える。 */
    private fun stripes(width: Int, height: Int, period: Int = 8, blur: Int = 0, contrast: Double = 1.0): IntArray {
        fun raw(x: Int) = if ((x / (period / 2)) % 2 == 0) 255.0 else 0.0
        return IntArray(width * height) { i ->
            val x = i % width
            val v = (x - blur..x + blur).map { raw(it.coerceIn(0, width - 1)) }.average()
            val c = (128 + (v - 128) * contrast).toInt().coerceIn(0, 255)
            (0xff shl 24) or (c shl 16) or (c shl 8) or c
        }
    }

    private fun of(argb: IntArray, width: Int, height: Int) = Sharpness.of(argb, width, height)!!

    @Test
    fun ぼけた行は鋭さが低い() {
        assertTrue(of(stripes(200, 24, blur = 1), 200, 24) < of(stripes(200, 24), 200, 24) * 0.5)
    }

    @Test
    fun 露出が変わってコントラストが変わっても同じ() {
        val sharp = of(stripes(200, 24), 200, 24)
        assertEquals(sharp, of(stripes(200, 24, contrast = 0.5), 200, 24), sharp * 0.05)
    }

    @Test
    fun 行の高さでそろえるので大きく写っても同じ() {
        // ちょうど2倍に写ったとき（縞も2倍）
        val sharp = of(stripes(200, 24), 200, 24)
        assertEquals(sharp, of(stripes(400, 48, period = 16), 400, 48), sharp * 0.05)
        // 整数倍でない大きさでも、ぼけた行よりはっきり鋭い
        assertTrue(of(stripes(300, 30, period = 10), 300, 30) > of(stripes(200, 24, blur = 1), 200, 24) * 2)
    }

    @Test
    fun 測れない行はnull() {
        // 一様（白飛び）と、小さすぎる行
        assertNull(Sharpness.of(IntArray(200 * 24) { -1 }, 200, 24))
        assertNull(Sharpness.of(stripes(2, 24), 2, 24))
    }
}
