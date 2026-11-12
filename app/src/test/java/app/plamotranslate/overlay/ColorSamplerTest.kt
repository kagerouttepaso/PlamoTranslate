package app.plamotranslate.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ColorSamplerTest {
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    private val white = rgb(255, 255, 255)
    private val black = rgb(0, 0, 0)

    @Test
    fun 白地の灰色の文字は黒にする() {
        assertEquals(black, ColorSampler.readable(rgb(110, 110, 110), white))
    }

    @Test
    fun 黒地の灰色の文字は白にする() {
        assertEquals(white, ColorSampler.readable(rgb(150, 150, 150), rgb(20, 20, 24)))
    }

    @Test
    fun 色の付いた文字は色味を残してコントラスト7まで濃くする() {
        val red = rgb(230, 60, 60)
        val c = ColorSampler.readable(red, white)
        assertTrue(ColorSampler.contrast(c, white) >= 7.0)
        // 赤みが残っている
        assertTrue(((c shr 16) and 0xFF) > (c and 0xFF))
    }

    @Test
    fun 十分に読める色はそのまま() {
        val navy = rgb(20, 30, 120)
        assertEquals(navy, ColorSampler.readable(navy, white))
    }
}
