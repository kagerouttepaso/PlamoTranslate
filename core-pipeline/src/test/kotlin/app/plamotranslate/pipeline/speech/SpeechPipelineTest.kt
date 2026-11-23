package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.async
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.*
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

    @Test(timeout = 10000)
    fun `閉じたカードの途中認識を中断して二枚の確定を順番どおり一度ずつ届ける`() {
        val events = interruptAndSpeakAgain(SpeechPipeline::cut)
        val cards = events.filterIsInstance<SpeechEvent.CardOpened>().map { it.card }
        assertEquals(2, cards.size)
        assertEquals(cards, events.filterIsInstance<SpeechEvent.CardFinished>().map { it.card })
    }

    @Test(timeout = 10000)
    fun `途中認識中に再開始したら新しいカードだけを確定する`() {
        val events = interruptAndSpeakAgain(SpeechPipeline::reset)
        val cards = events.filterIsInstance<SpeechEvent.CardOpened>().map { it.card }
        assertEquals(2, cards.distinct().size)
        assertEquals(listOf(cards.last()), events.filterIsInstance<SpeechEvent.CardFinished>().map { it.card })
    }

    private fun interruptAndSpeakAgain(interrupt: (SpeechPipeline) -> Unit) = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        val recognizer = object : SpeechRecognizer {
            override fun recognize(samples: FloatArray) = error("言語を指定する")
            override suspend fun transcribe(samples: FloatArray, language: String?, priority: RecognitionPriority): Recognition {
                if (priority == RecognitionPriority.PARTIAL) {
                    entered.complete(Unit)
                    try { awaitCancellation() } finally { cancelled.complete(Unit) }
                }
                return Recognition("This is final.", language, source = LanguageSource.SPECIFIED)
            }
            override fun close() = Unit
        }
        val pipeline = SpeechPipeline(LoudVad(), recognizer, heavy = true)
        pipeline.languages = SpeechLanguages(specified = "en")
        try {
            val events = async(start = CoroutineStart.UNDISPATCHED) { pipeline.events.takeWhile { it != SpeechEvent.Flushed }.toList() }
            fun speak() = repeat(30) { pipeline.accept(FloatArray(1600) { 0.5f }) }
            speak()
            entered.await()
            interrupt(pipeline)
            cancelled.await()
            speak()
            pipeline.flush()
            val observed = events.await()
            assertTrue(observed.filterIsInstance<SpeechEvent.CardFinished>().all { it.utterance?.text == "This is final." })
            observed
        } finally { pipeline.close() }
    }

    private fun expectFlush(commands: (SpeechPipeline) -> Unit) = runBlocking {
        val p = SpeechPipeline(LoudVad(), BrokenRecognizer())
        try {
            withTimeout(5_000) {
                val flushed = async(start = CoroutineStart.UNDISPATCHED) { p.events.first { it == SpeechEvent.Flushed } }
                commands(p)
                flushed.await()
            }
        } finally { p.close() }
        Unit
    }

    @Test
    fun 認識のワーカーが失敗しても止め終わりが届く() = expectFlush { p ->
        repeat(20) { p.accept(FloatArray(SpeechEngine.SAMPLE_RATE / 10) { 0.5f }) }
        p.flush()
    }

    @Test
    fun 閉じた後に止めても止め終わりが届く() = expectFlush { p ->
        p.close()
        p.flush()
    }

    @Test
    fun 止めている途中で閉じても止め終わりが届く() = expectFlush { p ->
        repeat(20) { p.accept(FloatArray(SpeechEngine.SAMPLE_RATE / 10) { 0.5f }) }
        p.flush()
        p.close()
    }
}
