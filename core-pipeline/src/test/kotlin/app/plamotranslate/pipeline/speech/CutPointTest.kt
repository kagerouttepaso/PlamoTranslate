package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区切る位置（[CutPoint]）。 */
class CutPointTest {
    private val frame = CutPoint.FRAME_SAMPLES

    /** 0.1秒ごとの音量を [levels] にした、ためた音声。 */
    private fun buffer(vararg levels: Float) = SpeechBuffer(prerollSamples = 1, maxSamples = frame * 100).apply {
        speaking(FloatArray(frame * levels.size) { levels[it / frame] })
    }

    @Test
    fun 一番静かな区間の真ん中で区切る() {
        val buf = buffer(0.8f, 0.8f, 0.01f, 0.8f, 0.8f)
        val at = CutPoint.longUtterance(buf, 0, buf.size)
        assertTrue("区切り $at", at in frame * 2..frame * 3)
        assertEquals(at, CutPoint.languageSeam(buf, 0, buf.size))
    }

    @Test
    fun 範囲の外は見ない() {
        val buf = buffer(0.01f, 0.8f, 0.5f, 0.8f, 0.8f)
        val at = CutPoint.longUtterance(buf, frame, buf.size)
        assertTrue("区切り $at", at in frame * 2..frame * 3)
    }

    @Test
    fun 範囲が短すぎれば範囲の終わりで区切る() {
        val buf = buffer(0.8f, 0.8f)
        assertEquals(frame + 10, CutPoint.longUtterance(buf, frame, frame + 10))
    }
}
