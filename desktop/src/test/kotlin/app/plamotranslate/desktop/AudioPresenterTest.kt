package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.RecognitionLoad
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.speech.Utterance
import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioPresenterTest {
    @Test
    fun `画面の準備と開始が重なっても認識器は一度だけ読み込む`() = runTest {
        var loads = 0
        val capture = FakeCapture()
        val presenter = AudioPresenter(backgroundScope, capture, { loads++; FakeSpeech() }, FakeTranslationEngine()::open,
            io = StandardTestDispatcher(testScheduler))
        launch(start = CoroutineStart.UNDISPATCHED) { presenter.prepare() }
        presenter.start()
        runCurrent()
        assertEquals(1, loads)
        assertEquals(AudioPhase.LISTENING, presenter.state.value.phase)
        presenter.close()
    }

    @Test
    fun `マイクの識別モデルが届いた後にそのモデルで準備する`() = runTest {
        var available = false
        var loads = 0
        val presenter = AudioPresenter(backgroundScope, FakeCapture(), { loads++; FakeSpeech() }, FakeTranslationEngine()::open,
            initialSelection = AudioSelection(recognition = DesktopRecognitionSettings(model = SpeechModel.QWEN3_ASR)),
            supportsAutomatic = { available }, io = StandardTestDispatcher(testScheduler))
        presenter.prepare()
        assertEquals(0, loads)
        available = true
        presenter.usePreparedRecognition(SpeechModel.QWEN3_ASR)
        presenter.prepare()
        assertEquals(1, loads)
        presenter.close()
    }

    @Test
    fun `会話中に相手の言語を選び直しても聞き取りを止めずに次の発話へ渡す`() = runTest {
        val speech = FakeSpeech(detected = "en")
        val engine = FakeTranslationEngine()
        val presenter = AudioPresenter(
            backgroundScope, FakeCapture(), { speech }, engine::open,
            io = StandardTestDispatcher(testScheduler),
        )
        presenter.start()
        runCurrent()
        assertEquals(AudioPhase.LISTENING, presenter.state.value.phase)
        assertTrue(presenter.state.value.canChooseLanguages)
        val other = presenter.state.value.partnerOptions.filterNotNull().first { it != Language.EN }
        presenter.selectPartner(other)
        runCurrent()
        assertEquals(AudioPhase.LISTENING, presenter.state.value.phase)
        assertEquals(other, presenter.state.value.selection.partner)
        assertEquals(1, presenter.state.value.live.size)
        presenter.stop()
        runCurrent()
        assertEquals(AudioPhase.IDLE, presenter.state.value.phase)
        assertEquals(1, presenter.state.value.turns.size)
        presenter.close()
    }

    @Test
    fun `再生音の字幕は指定した一方向で原文と訳を途中から確定へ置き換える`() = runTest {
        val speech = FakeSpeech()
        val engine = FakeTranslationEngine()
        val capture = FakeCapture()
        var preparedLanguage: String? = null
        val presenter = AudioPresenter(
            backgroundScope, capture, { selection -> preparedLanguage = selection.specifiedCode; speech },
            engine::open, AudioSelection(source = AudioSource.PLAYBACK),
            io = StandardTestDispatcher(testScheduler),
        )
        presenter.start()
        runCurrent()
        assertEquals(AudioSource.PLAYBACK, capture.source)
        assertEquals("en", preparedLanguage)
        assertEquals(preparedLanguage, speech.languages.specified)
        assertTrue(presenter.state.value.subtitleVisible)
        assertEquals(SubtitleLine("A partial sentence.", "途中訳"), presenter.state.value.subtitle)

        presenter.showSubtitle(false)
        assertFalse(presenter.state.value.subtitleVisible)
        assertEquals(AudioPhase.LISTENING, presenter.state.value.phase)
        presenter.stop()
        runCurrent()
        assertTrue(capture.stopped)
        assertEquals(SubtitleLine("A complete sentence.", "確定訳"), presenter.state.value.subtitle)
        assertEquals(listOf(Language.EN to Language.JA), engine.directions.distinct())
        assertTrue(presenter.state.value.turns.single().statsText.startsWith("区切りから確定まで1ms未満"))
        presenter.close()
    }

    @Test
    fun `認識器を準備している間に停止しても再開できる`() = runTest {
        val speech = FakeSpeech()
        val engine = FakeTranslationEngine()
        val presenter = AudioPresenter(
            backgroundScope, FakeCapture(), { speech }, engine::open,
            io = StandardTestDispatcher(testScheduler),
        )
        presenter.start()
        presenter.stop()
        runCurrent()
        assertEquals(AudioPhase.IDLE, presenter.state.value.phase)
        assertTrue(presenter.state.value.turns.isEmpty() && presenter.state.value.live.isEmpty())
        presenter.start()
        runCurrent()
        assertEquals(AudioPhase.LISTENING, presenter.state.value.phase)
        presenter.close()
    }

    @Test
    fun `マイクの自動判定で相手の言語が決まり自分への訳を作る`() = runTest {
        val speech = FakeSpeech(detected = "en")
        val engine = FakeTranslationEngine()
        val presenter = AudioPresenter(
            backgroundScope, FakeCapture(), { speech }, engine::open,
            io = StandardTestDispatcher(testScheduler),
        )
        presenter.start()
        runCurrent()
        assertEquals(null, speech.languages.specified)
        presenter.stop()
        runCurrent()
        val state = presenter.state.value
        assertEquals(Language.EN, state.partner)
        assertTrue(state.partnerFound)
        assertEquals(false, state.turns.single().direction.mine)
        assertEquals(listOf(Language.EN to Language.JA), engine.directions.distinct())
        assertTrue(state.canExport)
        presenter.close()
    }

    private class FakeCapture : AudioCapture {
        var source: AudioSource? = null
        var stopped = false

        override suspend fun capture(source: AudioSource, started: () -> Unit, accept: (FloatArray) -> Unit) {
            this.source = source
            started()
            accept(floatArrayOf(0.5f))
            try {
                awaitCancellation()
            } finally {
                stopped = true
            }
        }
    }

    private class FakeSpeech(private val detected: String? = null) : SpeechEngine {
        override var languages = SpeechLanguages.ANY
        override val events = kotlinx.coroutines.flow.MutableSharedFlow<SpeechEvent>(extraBufferCapacity = Int.MAX_VALUE)
        override val load = kotlinx.coroutines.flow.MutableStateFlow(RecognitionLoad())
        private var card = 0L

        private fun recognize(final: Boolean): Recognition =
            Recognition(if (final) "A complete sentence." else "A partial sentence.", detected)

        override fun accept(samples: FloatArray) {
            events.tryEmit(SpeechEvent.CardOpened(card))
            events.tryEmit(SpeechEvent.CardText(card, recognize(final = false).text, detected))
        }

        override fun flush() {
            cut()
            events.tryEmit(SpeechEvent.Flushed)
        }

        override fun cut() {
            val result = recognize(final = true)
            events.tryEmit(SpeechEvent.CardClosed(card))
            events.tryEmit(SpeechEvent.CardFinished(card, Utterance(card, result.text, result.lang, 0, 100, 0)))
        }

        override fun reset() { card++ }
        override fun retranscribe(utterance: Long, language: Language) = Unit
        override fun close() = Unit
    }

    private class FakeTranslationEngine : JobEngine {
        val directions = mutableListOf<Pair<Language?, Language>>()
        override var parallel = 1

        fun open(scope: CoroutineScope, options: TranslationStream.Options) =
            TranslationStream(scope, { TranslationStream.Backend(this, ModelOrigin("fake", 0)) }, options)

        override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int {
            val (id, request) = checkNotNull(source.next())
            record(request)
            source.onText(id, if (request.text.contains("partial")) "途中訳" else "確定訳")
            source.onFinished(id)
            return 1
        }

        private fun record(request: TranslationRequest) {
            directions += request.source to request.target
        }
    }
}
