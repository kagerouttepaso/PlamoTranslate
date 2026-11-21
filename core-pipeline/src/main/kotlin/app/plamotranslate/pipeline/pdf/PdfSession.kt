package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate

data class PdfImage<Image>(val zoom: Int, val content: Image)

data class PdfState<Image>(
    val target: Language = Language.JA,
    val name: String? = null,
    val page: Int = 0,
    val pageCount: Int = 0,
    val zoom: Int = 100,
    val sideBySide: Boolean = false,
    val opening: Boolean = false,
    val images: Map<Int, PdfImage<Image>> = emptyMap(),
    val layouts: Map<Int, List<PdfParagraph>> = emptyMap(),
    val translations: Map<Int, Map<Int, String>> = emptyMap(),
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
    data object SwitchView : PdfEvent<Nothing>
    data object Retry : PdfEvent<Nothing>
    data class Opened(val request: PdfRead.Open, val count: Int) : PdfEvent<Nothing>
    data class Rendered<Image>(val request: PdfRead.Render, val image: Image?) : PdfEvent<Image>
    data class Extracted(val request: PdfRead.Extract, val paragraphs: List<PdfParagraph>?) : PdfEvent<Nothing>
    data class Translated(val batch: Long, val update: SegmentUpdate) : PdfEvent<Nothing>
}

sealed interface PdfEffect {
    data class Read(val request: PdfRead) : PdfEffect
    data class Translate(val batch: Long, val target: Language, val segments: List<Segment>) : PdfEffect
    data object StopTranslation : PdfEffect
}

class PdfSession<Image> {
    private data class Batch(val id: Long, val page: Int, val pending: MutableSet<Int>)

    var state: PdfState<Image> = PdfState()
        private set
    private var visible = false
    private var document = 0L
    private var nextBatch = 0L
    private var reading: PdfRead? = null
    private var batch: Batch? = null

    fun reduce(event: PdfEvent<Image>): List<PdfEffect> = buildList {
        when (event) {
            is PdfEvent.Visible -> visible = event.visible
            is PdfEvent.Open -> {
                stopTranslation(this)
                state = PdfState(target = state.target, name = event.name, opening = true, sideBySide = state.sideBySide)
                val request = PdfRead.Open(++document, event.path)
                reading = request
                add(PdfEffect.Read(request))
            }
            is PdfEvent.Page -> if (event.index in 0 until state.pageCount) {
                state = state.copy(page = event.index, images = state.images.filterKeys { it in retainedImages(event.index) })
            }
            is PdfEvent.Zoom -> {
                val zoom = ZoomLevels[(ZoomLevels.indexOf(state.zoom) + event.direction.sign()).coerceIn(ZoomLevels.indices)]
                if (zoom != state.zoom) state = state.copy(zoom = zoom, undrawable = emptySet())
            }
            is PdfEvent.Target -> if (state.target != event.language) {
                stopTranslation(this)
                state = state.copy(target = event.language, translations = emptyMap(), untranslated = emptyMap())
            }
            PdfEvent.SwitchView -> state = state.copy(sideBySide = !state.sideBySide)
            PdfEvent.Retry -> state = state.copy(untranslated = state.untranslated - state.page)
            is PdfEvent.Opened -> if (accept(event.request)) {
                state = state.copy(opening = false, pageCount = event.count.coerceAtLeast(0), openFailed = event.count <= 0)
            }
            is PdfEvent.Rendered -> if (accept(event.request)) {
                val page = event.request.page
                val image = event.image
                if (page in retainedImages(state.page) && event.request.zoom == state.zoom) {
                    state = if (image == null) state.copy(undrawable = state.undrawable + page) else
                        state.copy(images = state.images + (page to PdfImage(event.request.zoom, image)))
                }
            }
            is PdfEvent.Extracted -> if (accept(event.request)) {
                state = if (event.paragraphs == null) state.copy(unreadable = state.unreadable + event.request.page) else
                    state.copy(layouts = state.layouts + (event.request.page to event.paragraphs))
            }
            is PdfEvent.Translated -> translateResult(event)
        }
        if (visible) {
            planRead()?.let { reading = it; add(PdfEffect.Read(it)) }
            planTranslation(this)
        } else stopTranslation(this)
    }

    private fun accept(request: PdfRead): Boolean {
        if (reading != request) return false
        reading = null
        return true
    }

    private fun planRead(): PdfRead? {
        if (reading != null || state.pageCount == 0) return null
        val pages = PdfTranslationPlan.pages(state.page, state.pageCount, ahead = ImageLookahead)
        fun render(page: Int) = PdfRead.Render(document, page, state.zoom).takeIf {
            state.images[page]?.zoom != state.zoom && page !in state.undrawable
        }
        fun extract(page: Int) = PdfRead.Extract(document, page).takeIf {
            page !in state.layouts && page !in state.unreadable
        }
        return render(state.page) ?: extract(state.page) ?: pages.drop(1).firstNotNullOfOrNull(::render)
            ?: pages.getOrNull(1)?.let(::extract)
    }

    private fun planTranslation(effects: MutableList<PdfEffect>) {
        val pending = PdfTranslationPlan.pages(state.page, state.pageCount, ahead = 1).mapNotNull { page ->
            val layout = state.layouts[page] ?: return@mapNotNull if (page in state.unreadable) page to emptyList() else null
            page to layout.indices.filter { it !in state.translations[page].orEmpty() && it !in state.untranslated[page].orEmpty() }
        }.toMap()
        val page = PdfTranslationPlan.next(state.page, state.pageCount, pending)
        if (batch?.page == page) return
        stopTranslation(effects)
        if (page != null) {
            val paragraphs = pending.getValue(page)
            val layout = state.layouts.getValue(page)
            val active = Batch(++nextBatch, page, paragraphs.toMutableSet())
            batch = active
            state = state.copy(translationPage = page)
            effects += PdfEffect.Translate(active.id, state.target, paragraphs.map { Segment(it.toLong(), layout[it].text) })
        }
    }

    private fun translateResult(event: PdfEvent.Translated) {
        val active = batch?.takeIf { it.id == event.batch } ?: return
        if (event.update is SegmentUpdate.Partial || !active.pending.remove(event.update.id.toInt())) return
        val page = active.page
        val index = event.update.id.toInt()
        state = when (val update = event.update) {
            is SegmentUpdate.Final -> state.copy(translations = state.translations +
                (page to (state.translations[page].orEmpty() + (index to update.text))))
            is SegmentUpdate.Failed -> state.copy(untranslated = state.untranslated +
                (page to (state.untranslated[page].orEmpty() + index)))
            is SegmentUpdate.Partial -> state
        }
    }

    private fun stopTranslation(effects: MutableList<PdfEffect>) {
        if (batch == null) return
        batch = null
        state = state.copy(translationPage = null)
        effects += PdfEffect.StopTranslation
    }

    private fun retainedImages(current: Int) = (current - 1)..(current + ImageLookahead)
    private fun Int.sign() = compareTo(0)

    companion object {
        private const val ImageLookahead = 2
        val ZoomLevels = listOf(50, 75, 100, 125, 150, 200, 250)
    }
}
