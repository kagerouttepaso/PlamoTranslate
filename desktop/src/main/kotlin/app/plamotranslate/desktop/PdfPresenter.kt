package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.EventLoop
import app.plamotranslate.pipeline.pdf.PdfEffect
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfSession
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

internal class PdfPresenter(
    private val scope: CoroutineScope,
    private val reader: PdfReader,
    private val openTranslation: (CoroutineScope, TranslationStream.Options) -> TranslationStream,
    private val chooseFile: () -> Path?,
) {
    private val session = PdfSession<PdfPageImage>()
    private val mutableState = MutableStateFlow(session.state)
    val state = mutableState.asStateFlow()
    private val events = EventLoop<PdfEvent<PdfPageImage>, PdfEffect>({ event ->
        session.reduce(event).also { mutableState.value = session.state }
    }, ::perform)

    private var stream: TranslationStream? = null
    private var subscription: Job? = null
    private val batches = mutableMapOf<Long, Long>()

    init {
        scope.launch { reader.events.collect(events::dispatch) }
    }

    fun setVisible(visible: Boolean) = events.dispatch(PdfEvent.Visible(visible))
    fun choose() { chooseFile()?.let(::open) }
    fun page(index: Int) = events.dispatch(PdfEvent.Page(index))
    fun zoom(direction: Int) = events.dispatch(PdfEvent.Zoom(direction))
    fun selectTarget(target: Language) = events.dispatch(PdfEvent.Target(target))
    fun setParallel(slots: Int) = events.dispatch(PdfEvent.Parallel(slots))
    fun start() = events.dispatch(PdfEvent.Start)
    fun stop() = events.dispatch(PdfEvent.Stop)
    fun switchView() = events.dispatch(PdfEvent.SwitchView)
    fun retry() = events.dispatch(PdfEvent.Retry)

    fun drop(files: List<String>) {
        files.firstNotNullOfOrNull { value ->
            try {
                val file = if (value.startsWith("file:", ignoreCase = true)) Path.of(java.net.URI.create(value)) else Path.of(value)
                file.takeIf { it.fileName?.toString()?.endsWith(".pdf", ignoreCase = true) == true }
            } catch (_: IllegalArgumentException) {
                null
            }
        }?.let(::open)
    }

    private fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        events.dispatch(PdfEvent.Open(normalized.toString(), normalized.fileName.toString()))
    }

    private fun perform(effect: PdfEffect) {
        when (effect) {
            is PdfEffect.Read -> reader.read(effect.request)
            is PdfEffect.WritePage -> reader.write(effect)
            PdfEffect.StopTranslation -> {
                batches.clear()
                subscription?.cancel()
                subscription = null
                stream?.close()
                stream = null
            }
            is PdfEffect.Translate -> {
                val opened = stream ?: openTranslation(scope, TranslationStream.Options(source = null, target = effect.target))
                    .also { active ->
                        stream = active
                        subscription = scope.launch {
                            active.updates.collect { update ->
                                val batch = if (update is SegmentUpdate.Partial) batches[update.id] else batches.remove(update.id)
                                if (batch != null) events.dispatch(PdfEvent.Translated(batch, update))
                            }
                        }
                    }
                effect.segments.forEach { batches[it.id] = effect.batch }
                opened.submitBatch(effect.segments)
            }
        }
    }
}
