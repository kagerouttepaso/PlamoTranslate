package app.plamotranslate.pipeline.pdf

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate

enum class PdfPhase { Idle, Translating, Paused, Finished }

data class PdfState(
    val target: Language = Language.JA,
    val name: String? = null,
    val page: Int = 0,
    val pageCount: Int = 0,
    val opening: Boolean = false,
    val phase: PdfPhase = PdfPhase.Idle,
    val layouts: Map<Int, List<PdfParagraph>> = emptyMap(),
    val translations: Map<Int, Map<Int, String>> = emptyMap(),
    /** キーは [PdfTitles.titleKey] で正規化した原文の見出し。 */
    val titleTranslations: Map<String, String> = emptyMap(),
    val working: Set<String> = emptySet(),
    val previews: Map<String, PdfPreviewLine> = emptyMap(),
    val writtenPages: Set<Int> = emptySet(),
    val writeFailed: Set<Int> = emptySet(),
    val translationPage: Int? = null,
    val openFailed: Boolean = false,
    val unreadable: Set<Int> = emptySet(),
    val untranslated: Map<Int, Set<Int>> = emptyMap(),
) {
    fun titleTranslation(title: String): String? = titleTranslations[PdfTitles.titleKey(title)]
}

sealed interface PdfRead {
    val document: Long
    data class Open(override val document: Long, val path: String) : PdfRead
    data class Extract(override val document: Long, val page: Int) : PdfRead
}

sealed interface PdfEvent {
    data class Open(val path: String, val name: String) : PdfEvent
    /** 表示の中心にあるページ。0 始まり。 */
    data class Page(val index: Int) : PdfEvent
    data class Target(val language: Language) : PdfEvent
    data class Parallel(val slots: Int) : PdfEvent
    data object Start : PdfEvent
    data object Stop : PdfEvent
    data object Retry : PdfEvent
    data class Opened(val request: PdfRead.Open, val count: Int) : PdfEvent
    data class Extracted(val request: PdfRead.Extract, val pageText: PdfPageText?) : PdfEvent
    data class Translated(val batch: Long, val update: SegmentUpdate) : PdfEvent
    data class Written(val document: Long, val page: Int, val success: Boolean) : PdfEvent
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
class PdfSession {
    var state = PdfState()
        private set

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

    fun reduce(event: PdfEvent): List<PdfEffect> = buildList {
        when (event) {
            is PdfEvent.Open -> open(event.path, event.name, 0, state.target, PdfPhase.Idle, this)
            is PdfEvent.Page -> if (event.index in 0 until state.pageCount) {
                state = state.copy(page = event.index)
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
        if (state.phase == PdfPhase.Translating) {
            planExtraction()?.let {
                reading = it
                add(PdfEffect.Read(it))
            }
            planTranslation(this)
        }
    }

    private fun open(path: String, name: String, page: Int, target: Language, phase: PdfPhase, effects: MutableList<PdfEffect>) {
        cancel(effects)
        this.path = path
        plan = null
        confirmed.clear()
        writing.clear()
        state = PdfState(target = target, name = name, page = page, opening = true, phase = phase)
        val request = PdfRead.Open(++document, path)
        reading = request
        effects += PdfEffect.Read(request)
    }

    private fun accept(request: PdfRead): Boolean {
        if (reading != request) return false
        reading = null
        return true
    }

    private fun planExtraction(): PdfRead.Extract? {
        if (reading != null || plan != null) return null
        val page = (0 until state.pageCount).firstOrNull { it !in state.layouts && it !in state.unreadable } ?: return null
        return PdfRead.Extract(document, page)
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
        val replaced = mutableListOf<PdfParagraph>()
        for (page in queue.pagesFor(text)) {
            val updates = buildMap {
                state.layouts.getValue(page).forEachIndexed { index, paragraph ->
                    if (paragraph.text == text && PdfMarkupTranslation.shouldReplace(paragraph, update.text)) {
                        put(index, update.text)
                        replaced += paragraph
                    }
                }
            }
            if (updates.isNotEmpty()) byPage[page] = byPage[page].orEmpty() + updates
        }
        val titles = PdfTitles.titleTranslations(replaced, mapOf(text to update.text))
        state = state.copy(translations = byPage, titleTranslations = state.titleTranslations + titles,
            working = state.working - text, previews = state.previews - text)
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

    private companion object {
        private val startable = setOf(PdfPhase.Idle, PdfPhase.Paused)
    }
}
