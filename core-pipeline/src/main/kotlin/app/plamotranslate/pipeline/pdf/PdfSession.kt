package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate

data class PdfImage<Image>(val zoom: Int, val content: Image)

enum class PdfPhase { Idle, Translating, Paused, Finished }

data class PdfState<Image>(
    val target: Language = Language.JA,
    val name: String? = null,
    val page: Int = 0,
    val pageCount: Int = 0,
    val zoom: Int = 100,
    val sideBySide: Boolean = false,
    val opening: Boolean = false,
    val phase: PdfPhase = PdfPhase.Idle,
    val images: Map<Int, PdfImage<Image>> = emptyMap(),
    val layouts: Map<Int, List<PdfParagraph>> = emptyMap(),
    val translations: Map<Int, Map<Int, String>> = emptyMap(),
    val working: Set<String> = emptySet(),
    val previews: Map<String, PdfPreviewLine> = emptyMap(),
    val writtenPages: Set<Int> = emptySet(),
    val writeFailed: Set<Int> = emptySet(),
    val translationPage: Int? = null,
    val openFailed: Boolean = false,
    val unreadable: Set<Int> = emptySet(),
    val undrawable: Set<Int> = emptySet(),
    val untranslated: Map<Int, Set<Int>> = emptyMap(),
)

sealed interface PdfRead {
    val document: Long
    data class Open(override val document: Long, val path: String) : PdfRead
    data class Render(override val document: Long, val page: Int, val zoom: Int) : PdfRead
    data class Extract(override val document: Long, val page: Int) : PdfRead
}

sealed interface PdfEvent<out Image> {
    data class Visible(val visible: Boolean) : PdfEvent<Nothing>
    data class Open(val path: String, val name: String) : PdfEvent<Nothing>
    data class Page(val index: Int) : PdfEvent<Nothing>
    data class Zoom(val direction: Int) : PdfEvent<Nothing>
    data class Target(val language: Language) : PdfEvent<Nothing>
    data class Parallel(val slots: Int) : PdfEvent<Nothing>
    data object Start : PdfEvent<Nothing>
    data object Stop : PdfEvent<Nothing>
    data object SwitchView : PdfEvent<Nothing>
    data object Retry : PdfEvent<Nothing>
    data class Opened(val request: PdfRead.Open, val count: Int) : PdfEvent<Nothing>
    data class Rendered<Image>(val request: PdfRead.Render, val image: Image?) : PdfEvent<Image>
    data class Extracted(val request: PdfRead.Extract, val pageText: PdfPageText?) : PdfEvent<Nothing>
    data class Translated(val batch: Long, val update: SegmentUpdate) : PdfEvent<Nothing>
    data class Written(val document: Long, val page: Int, val success: Boolean) : PdfEvent<Nothing>
}

sealed interface PdfEffect {
    data class Read(val request: PdfRead) : PdfEffect
    data class Translate(val batch: Long, val target: Language, val segments: List<Segment>) : PdfEffect
    data class WritePage(
        val document: Long,
        val page: Int,
        val paragraphs: List<PdfParagraph>,
        val translations: Map<String, String>,
    ) : PdfEffect
    data object StopTranslation : PdfEffect
}

/** 読み取り・翻訳・ページの書き込みを発行し、古い文書や中止した依頼からの応答を捨てる。 */
class PdfSession<Image> {
    var state: PdfState<Image> = PdfState()
        private set

    private var visible = false
    private var path: String? = null
    private var document = 0L
    private var parallel = 1
    private var nextBatch = 0L
    private var nextSegment = 0L
    private var reading: PdfRead? = null
    private var plan: PdfTranslationPlan? = null
    private val confirmed = mutableMapOf<String, String>()
    private val inflight = linkedMapOf<Long, MutableMap<Long, String>>()
    private val writing = mutableSetOf<Int>()

    fun reduce(event: PdfEvent<Image>): List<PdfEffect> = buildList {
        when (event) {
            is PdfEvent.Visible -> visible = event.visible
            is PdfEvent.Open -> open(event.path, event.name, 0, state.target, PdfPhase.Idle, this)
            is PdfEvent.Page -> if (event.index in 0 until state.pageCount) {
                state = state.copy(page = event.index, images = state.images.filterKeys { it in retainedImages(event.index) })
            }
            is PdfEvent.Zoom -> {
                val index = ZoomLevels.indexOf(state.zoom) + event.direction.compareTo(0)
                val zoom = ZoomLevels[index.coerceIn(ZoomLevels.indices)]
                if (zoom != state.zoom) state = state.copy(zoom = zoom, undrawable = emptySet())
            }
            is PdfEvent.Target -> if (state.target != event.language) {
                val current = path
                if (current != null && state.phase != PdfPhase.Idle) {
                    val phase = if (state.phase == PdfPhase.Translating) PdfPhase.Translating else PdfPhase.Idle
                    open(current, state.name.orEmpty(), state.page, event.language, phase, this)
                } else {
                    state = state.copy(target = event.language)
                }
            }
            is PdfEvent.Parallel -> parallel = event.slots.coerceAtLeast(1)
            PdfEvent.SwitchView -> state = state.copy(sideBySide = !state.sideBySide)
            PdfEvent.Start, PdfEvent.Retry -> if (state.pageCount > 0 && state.phase in startable) {
                state = state.copy(phase = PdfPhase.Translating, untranslated = emptyMap())
            }
            PdfEvent.Stop -> if (state.phase == PdfPhase.Translating) {
                cancel(this)
                state = state.copy(phase = PdfPhase.Paused)
            }
            is PdfEvent.Opened -> if (accept(event.request)) {
                state = state.copy(opening = false, pageCount = event.count.coerceAtLeast(0),
                    page = state.page.coerceAtMost((event.count - 1).coerceAtLeast(0)), openFailed = event.count <= 0,
                    phase = if (event.count > 0) state.phase else PdfPhase.Idle)
            }
            is PdfEvent.Rendered -> if (accept(event.request)) renderResult(event)
            is PdfEvent.Extracted -> if (accept(event.request)) {
                state = if (event.pageText == null) state.copy(unreadable = state.unreadable + event.request.page) else
                    state.copy(layouts = state.layouts + (event.request.page to PdfParagraphs.page(event.pageText)))
            }
            is PdfEvent.Translated -> translateResult(event, this)
            is PdfEvent.Written -> if (event.document == document && writing.remove(event.page)) {
                state = if (event.success) state.copy(writtenPages = state.writtenPages + event.page) else
                    state.copy(writeFailed = state.writeFailed + event.page)
            }
        }
        if (visible || state.phase == PdfPhase.Translating) {
            planRead()?.let {
                reading = it
                add(PdfEffect.Read(it))
            }
        }
        if (state.phase == PdfPhase.Translating) planTranslation(this)
    }

    private fun open(path: String, name: String, page: Int, target: Language, phase: PdfPhase, effects: MutableList<PdfEffect>) {
        cancel(effects)
        this.path = path
        plan = null
        confirmed.clear()
        writing.clear()
        state = PdfState(target = target, name = name, page = page, zoom = state.zoom,
            opening = true, sideBySide = state.sideBySide, phase = phase)
        val request = PdfRead.Open(++document, path)
        reading = request
        effects += PdfEffect.Read(request)
    }

    private fun accept(request: PdfRead): Boolean {
        if (reading != request) return false
        reading = null
        return true
    }

    private fun renderResult(event: PdfEvent.Rendered<Image>) {
        val (page, zoom) = event.request.let { it.page to it.zoom }
        if (page !in retainedImages(state.page) || zoom != state.zoom) return
        val image = event.image
        state = if (image == null) state.copy(undrawable = state.undrawable + page) else
            state.copy(images = state.images + (page to PdfImage(zoom, image)))
    }

    private fun planRead(): PdfRead? {
        if (reading != null || state.pageCount == 0) return null
        fun render(page: Int) = PdfRead.Render(document, page, state.zoom).takeIf {
            visible && state.images[page]?.zoom != state.zoom && page !in state.undrawable
        }
        render(state.page)?.let { return it }
        if (state.phase == PdfPhase.Translating && plan == null) {
            val page = (0 until state.pageCount).firstOrNull { it !in state.layouts && it !in state.unreadable }
            if (page != null) return PdfRead.Extract(document, page)
        }
        return retainedImages(state.page).filter { it != state.page }.firstNotNullOfOrNull(::render)
    }

    private fun planTranslation(effects: MutableList<PdfEffect>) {
        if (state.pageCount == 0 || state.layouts.size + state.unreadable.size < state.pageCount) return
        val queue = plan ?: PdfTranslationPlan(PdfParagraphs.classify(state.layouts).also {
            state = state.copy(layouts = it)
        }).also { plan = it }
        val working = state.working.toMutableSet()
        for (part in queue.batches(state.page, parallel - inflight.size, confirmed.keys + working)) {
            val segments = part.map { text -> Segment(++nextSegment, text) }
            val batch = ++nextBatch
            inflight[batch] = segments.associateTo(linkedMapOf()) { it.id to it.text }
            working.addAll(part)
            effects += PdfEffect.Translate(batch, state.target, segments)
        }
        state = state.copy(working = working, translationPage = working.firstOrNull()?.let { text ->
            queue.pagesFor(text).let { if (state.page in it) state.page else it.firstOrNull() }
        })
        if (confirmed.size == queue.sources.size && writing.isEmpty()) {
            cancel(effects)
            state = state.copy(phase = PdfPhase.Finished)
        }
    }

    private fun translateResult(event: PdfEvent.Translated, effects: MutableList<PdfEffect>) {
        val queue = plan ?: return
        val active = inflight[event.batch] ?: return
        val update = event.update
        val text = active[update.id] ?: return
        if (update is SegmentUpdate.Partial) {
            val preview = PdfPreview.line(update.text, update.lineComplete)
            state = state.copy(previews = if (preview.text.isEmpty()) state.previews - text else state.previews + (text to preview))
            return
        }
        if (update is SegmentUpdate.Failed) {
            state = state.copy(phase = PdfPhase.Paused, untranslated = queue.pagesFor(text).associateWith { page ->
                state.layouts.getValue(page).indices.filter { state.layouts.getValue(page)[it].text == text }.toSet()
            })
            cancel(effects)
            return
        }
        check(update is SegmentUpdate.Final)
        active.remove(update.id)
        if (active.isEmpty()) inflight.remove(event.batch)
        confirmed[text] = update.text
        val byPage = state.translations.toMutableMap()
        for (page in queue.pagesFor(text)) {
            val updates = buildMap {
                state.layouts.getValue(page).forEachIndexed { index, paragraph ->
                    if (paragraph.text == text && update.text.isNotBlank() &&
                        PdfMarkupTranslation.shouldReplace(paragraph, update.text)) put(index, update.text)
                }
            }
            if (updates.isNotEmpty()) byPage[page] = byPage[page].orEmpty() + updates
        }
        state = state.copy(translations = byPage, working = state.working - text, previews = state.previews - text)
        val ready = queue.completedPages(listOf(text), confirmed.keys)
        for (page in ready) {
            if (!writing.add(page)) continue
            val paragraphs = state.layouts.getValue(page)
            val results = paragraphs.mapNotNull { p -> confirmed[p.text]?.let { p.text to it } }.toMap()
            effects += PdfEffect.WritePage(document, page, paragraphs, results)
        }
    }

    private fun cancel(effects: MutableList<PdfEffect>) {
        if (state.phase == PdfPhase.Translating || inflight.isNotEmpty()) effects += PdfEffect.StopTranslation
        inflight.clear()
        state = state.copy(working = emptySet(), previews = emptyMap(), translationPage = null)
    }

    private fun retainedImages(page: Int) = (page - 1).coerceAtLeast(0)..(page + ImageLookahead).coerceAtMost(state.pageCount - 1)

    companion object {
        private const val ImageLookahead = 2
        private val startable = setOf(PdfPhase.Idle, PdfPhase.Paused)
        val ZoomLevels = listOf(50, 75, 100, 125, 150, 200, 250)
    }
}
