package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechBufferTest {
    @Test
    fun 話し始めに直前の音声を頭に足す() {
        val buf = SpeechBuffer(prerollSamples = 3, maxSamples = 100)
        buf.idle(floatArrayOf(1f, 2f, 3f, 4f, 5f))
        buf.speaking(floatArrayOf(6f, 7f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f, 6f, 7f), buf.snapshot(), 0f)
    }

    @Test
    fun 話し終わったら次の発話は新しくためる() {
        val buf = SpeechBuffer(prerollSamples = 2, maxSamples = 100)
        buf.speaking(floatArrayOf(1f, 2f))
        buf.idle(floatArrayOf(3f))
        assertFalse(buf.active)
        buf.speaking(floatArrayOf(4f))
        assertArrayEquals(floatArrayOf(3f, 4f), buf.snapshot(), 0f)
    }

    @Test
    fun 区切るときは先頭を取り出し残りを途切れずに続ける() {
        val buf = SpeechBuffer(prerollSamples = 2, maxSamples = 100)
        buf.speaking(floatArrayOf(1f, 2f, 3f, 4f))
        assertArrayEquals(floatArrayOf(1f, 2f), buf.take(2), 0f)
        buf.speaking(floatArrayOf(5f))
        assertArrayEquals(floatArrayOf(3f, 4f, 5f), buf.snapshot(), 0f)
        assertTrue(buf.active)
    }

    @Test
    fun 上限より長くはためない() {
        val buf = SpeechBuffer(prerollSamples = 1, maxSamples = 4)
        buf.speaking(FloatArray(10) { it.toFloat() })
        assertEquals(4, buf.size)
    }
}
