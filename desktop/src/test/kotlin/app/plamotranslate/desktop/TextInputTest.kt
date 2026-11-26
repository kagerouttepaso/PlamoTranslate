package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.speech.RecognitionLoad
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.Utterance
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.ModelState
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class TextInputTest {
    @Test
    fun `貼り付けで翻訳し 訳す方向の入れ替えで訳文を原文にする`() = runTest {
        val target = mutableListOf<Language>()
        val clipboard = mutableListOf<String>()
        val presenter = presenter(clipboard, target)
        assertFalse(presenter.state.value.canSwap)
        presenter.selectSource(Language.EN)
        presenter.pasteAndTranslate()
        runCurrent()
        assertEquals("Hello", presenter.state.value.input)
        assertEquals("訳：Hello", presenter.state.value.output)
        presenter.copyInput()
        presenter.copyOutput()
        presenter.swap()
        assertEquals(listOf("Hello", "訳：Hello"), clipboard)
        assertEquals(Language.JA, presenter.state.value.source)
        assertEquals(Language.EN, presenter.state.value.target)
        assertEquals(listOf(Language.EN), target)
        assertEquals("訳：Hello", presenter.state.value.input)
        assertEquals("", presenter.state.value.output)
        presenter.clear()
        assertEquals("", presenter.state.value.input)
    }

    @Test
    fun `マイクを停止すると最初の発話を確定し 原文に足して訳す`() = runTest {
        val presenter = presenter(mutableListOf(), mutableListOf())
        presenter.edit("Hello")
        presenter.startVoice()
        runCurrent()
        assertEquals(TextVoicePhase.LISTENING, presenter.state.value.voice)
        assertEquals("Hello w…", presenter.state.value.input)
        presenter.stopVoice()
        runCurrent()
        assertEquals(TextVoicePhase.IDLE, presenter.state.value.voice)
        assertEquals("Hello world", presenter.state.value.input)
        assertEquals("訳：Hello world", presenter.state.value.output)
    }

    @Test
    fun `音声翻訳がQwenを使用中は音声入力に案内を出し SenseVoiceなら開始する`() = runTest {
        var model = SpeechModel.QWEN3_ASR
        val presenter = presenter(mutableListOf(), mutableListOf(), recognition = { DesktopRecognitionSettings(model = model) },
            qwenInUse = { true })
        presenter.startVoice()
        assertEquals(TextVoicePhase.IDLE, presenter.state.value.voice)
        assertEquals("音声翻訳を止めてから、音声入力を使ってください。", presenter.state.value.error)

        model = SpeechModel.SENSE_VOICE
        presenter.startVoice()
        runCurrent()
        assertEquals(TextVoicePhase.LISTENING, presenter.state.value.voice)
        assertEquals(null, presenter.state.value.error)
        presenter.stopVoice()
        runCurrent()
    }

    private fun TestScope.presenter(
        clipboard: MutableList<String>,
        targets: MutableList<Language>,
        recognition: () -> DesktopRecognitionSettings = { DesktopRecognitionSettings() },
        qwenInUse: () -> Boolean = { false },
    ): TextPresenter {
        val audio = AudioCapture { _, started, accept ->
            started()
            accept(floatArrayOf(0.5f))
            awaitCancellation()
        }
        return TextPresenter(backgroundScope, MutableStateFlow(ModelState.Ready(Path.of("fake"), 1, 1, false, 0)),
            { scope, options -> TranslationStream(scope, { TranslationStream.Backend(FakeTranslation, ModelOrigin("fake", 0)) }, options) },
            { "Hello" }, { clipboard.add(it) }, DesktopTextVoice(audio, { _, _ -> FakeSpeech() }, StandardTestDispatcher(testScheduler)),
            recognition, { targets.add(it) }, qwenInUse)
    }

    private object FakeTranslation : JobEngine {
        override var parallel = 1

        override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int {
            val (id, request) = source.next() ?: return 0
            source.onText(id, "訳：${request.text}")
            source.onFinished(id)
            return 1
        }
    }

    private class FakeSpeech : SpeechEngine {
        override var languages = SpeechLanguages.ANY
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<SpeechEvent>(extraBufferCapacity = Int.MAX_VALUE)
        override val load = kotlinx.coroutines.flow.MutableStateFlow(RecognitionLoad())

        override fun accept(samples: FloatArray) {
            events.tryEmit(SpeechEvent.CardOpened(1))
            events.tryEmit(SpeechEvent.CardText(1, "w…", "en"))
        }

        override fun flush() {
            events.tryEmit(SpeechEvent.CardFinished(1, Utterance(1, "world", "en", 0, 0, 0)))
            events.tryEmit(SpeechEvent.Flushed)
        }

        override fun cut() = Unit
        override fun reset() = Unit
        override fun retranscribe(utterance: Long, language: Language) = Unit
        override fun close() = Unit
    }
}
