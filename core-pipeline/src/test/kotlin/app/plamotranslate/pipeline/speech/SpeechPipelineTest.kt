package app.plamotranslate.pipeline.speech

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 音声認識の流れ（[SpeechPipeline]）の止め終わりの約束。止める側は [SpeechEvent.Flushed] を待って聞き終えるので、
 * 届かないと会話翻訳の停止が終わらなくなる（画面からは気づきにくい）。
 */
class SpeechPipelineTest {
    /** 音が0でなければ話している、とする VAD。 */
    private class LoudVad : VoiceActivity {
        override fun accept(samples: FloatArray) = VoiceSignal(samples.any { it != 0f }, false)
        override fun flush() = Unit
        override fun reset() = Unit
        override fun close() = Unit
    }

    /** 書き起こすたびにネイティブの失敗のような Error を投げる認識器。 */
    private class BrokenRecognizer : SpeechRecognizer {
        override fun recognize(samples: FloatArray): Recognition = throw OutOfMemoryError("偽の失敗")
        override fun close() = Unit
    }

    private fun pipeline(): Pair<SpeechPipeline, CountDownLatch> {
        val flushed = CountDownLatch(1)
        val p = SpeechPipeline(LoudVad(), BrokenRecognizer())
        p.listener = { if (it == SpeechEvent.Flushed) flushed.countDown() }
        return p to flushed
    }

    @Test
    fun 認識のワーカーが失敗しても止め終わりが届く() {
        val (p, flushed) = pipeline()
        repeat(20) { p.accept(FloatArray(SpeechEngine.SAMPLE_RATE / 10) { 0.5f }) }
        p.flush()
        assertTrue(flushed.await(5, TimeUnit.SECONDS))
        p.close()
    }

    @Test
    fun 閉じた後に止めても止め終わりが届く() {
        val (p, flushed) = pipeline()
        p.close()
        p.flush()
        assertTrue(flushed.await(5, TimeUnit.SECONDS))
    }

    @Test
    fun 止めている途中で閉じても止め終わりが届く() {
        val (p, flushed) = pipeline()
        repeat(20) { p.accept(FloatArray(SpeechEngine.SAMPLE_RATE / 10) { 0.5f }) }
        p.flush()
        p.close()
        assertTrue(flushed.await(5, TimeUnit.SECONDS))
    }
}
