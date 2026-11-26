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

    private fun long(buf: SpeechBuffer, breaks: List<SpeechBreak> = emptyList(), from: Int = 0) =
        CutPoint.longUtterance(buf, breakFrom = from, quietFrom = from, to = buf.size, breaks = breaks)

    @Test
    fun 言語の境界は一番静かな区間の真ん中で区切る() {
        val buf = buffer(0.8f, 0.8f, 0.01f, 0.8f, 0.8f)
        val at = CutPoint.languageSeam(buf, 0, buf.size)
        assertTrue("区切り $at", at in frame * 2..frame * 3)
    }

    @Test
    fun 長い発話は切れ目が無ければ一番静かな区間で区切る() {
        val buf = buffer(0.8f, 0.8f, 0.01f, 0.8f, 0.8f)
        val at = long(buf)
        assertTrue("区切り $at", at in frame * 2..frame * 3)
    }

    @Test
    fun 長い発話は読点より文の終わりで区切り時刻の後ろの間にそろえる() {
        // 文の終わりの時刻（0.5秒）の後ろに間（0.6〜0.7秒）、読点（1.0秒）の後ろにも間（1.1〜1.2秒）がある
        val buf = buffer(0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.8f, 0.01f, 0.8f, 0.8f, 0.8f, 0.8f, 0.01f, 0.8f, 0.8f)
        val at = long(buf, listOf(SpeechBreak(frame * 5, sentence = true), SpeechBreak(frame * 10, sentence = false)))
        assertTrue("区切り $at", at in frame * 6..frame * 7)
        // 文の終わりが無ければ読点で
        val clause = long(buf, listOf(SpeechBreak(frame * 10, sentence = false)))
        assertTrue("区切り $clause", clause in frame * 11..frame * 12)
    }

    @Test
    fun 長い発話は書き起こしを頼んだ文の終わりより前で区切らない() {
        // 文の終わり（0.5秒）の直前（0.3〜0.4秒）が一番静かでも、文を外さないように文の終わりより後ろで区切る
        val buf = buffer(0.8f, 0.8f, 0.8f, 0.01f, 0.8f, 0.05f, 0.8f, 0.8f)
        val breaks = listOf(SpeechBreak(frame * 5, sentence = true))
        assertTrue(long(buf, breaks) < frame * 5)
        val at = CutPoint.longUtterance(buf, breakFrom = 0, quietFrom = 0, to = buf.size, breaks = breaks, kept = frame * 5)
        assertTrue("区切り $at", at in frame * 5..frame * 6)
        // 文の終わりが範囲の終わりより後ろなら、文の終わりで区切る
        assertEquals(frame * 7, CutPoint.longUtterance(buf, 0, 0, frame * 6, breaks, kept = frame * 7))
    }

    @Test
    fun 範囲の外の切れ目は使わない() {
        val buf = buffer(0.01f, 0.8f, 0.8f, 0.5f, 0.8f, 0.8f)
        val at = long(buf, listOf(SpeechBreak(frame / 2, sentence = true)), from = frame)
        assertTrue("区切り $at", at in frame * 3..frame * 4)
    }

    @Test
    fun 範囲が短すぎれば範囲の終わりで区切る() {
        val buf = buffer(0.8f, 0.8f)
        assertEquals(frame + 10, CutPoint.languageSeam(buf, frame, frame + 10))
    }
}
