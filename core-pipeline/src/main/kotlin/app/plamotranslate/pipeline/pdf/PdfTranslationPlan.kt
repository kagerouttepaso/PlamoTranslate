package app.plamotranslate.pipeline.pdf

import app.plamotranslate.pipeline.translation.SequentialCore
import kotlin.math.roundToLong

/** 文書全体の原文を共有し、空いた翻訳枠に送るバッチと、書けるページを選ぶ。 */
class PdfTranslationPlan(pages: Map<Int, List<PdfParagraph>>) {
    private enum class Kind(val tier: Int) { Heading(0), Lead(1), Body(2), Control(2) }
    private data class Item(val text: String, val kind: Kind)

    private val ordered = pages.toSortedMap().mapValues { (_, paras) -> readingOrder(paras.filter { it.skip == null }) }
        .filterValues { it.isNotEmpty() }
    private val pagesOf = buildMap<String, List<Int>> {
        val index = linkedMapOf<String, MutableList<Int>>()
        for ((page, items) in ordered) for (item in items) index.getOrPut(item.text) { mutableListOf() }.add(page)
        putAll(index)
    }
    val sources: Set<String> get() = pagesOf.keys

    fun pagesFor(text: String): List<Int> = pagesOf[text].orEmpty()

    /** [unavailable] は確定済みと送信済みの原文。表示を移動しても進行中の依頼は維持する。 */
    fun batches(focus: Int, slots: Int, unavailable: Set<String> = emptySet()): List<List<String>> {
        if (slots <= 0) return emptyList()
        val scheduled = unavailable.toMutableSet()
        return buildList {
            for (page in ordered.keys.sortedBy { distance(it, focus) }) {
                val items = ordered.getValue(page).filterNot { it.text in scheduled }
                for (part in pageBatches(items, slots)) {
                    val texts = part.map { it.text }
                    add(texts)
                    scheduled.addAll(texts)
                    if (size == slots) return@buildList
                }
            }
        }
    }

    fun isPageComplete(page: Int, confirmed: Set<String>): Boolean =
        ordered[page]?.all { it.text in confirmed } == true

    /** 今回確定した原文で書けるようになったページ。すでに発行したページは呼ぶ側で管理する。 */
    fun completedPages(results: Iterable<String>, confirmed: Set<String>): List<Int> =
        results.flatMap(::pagesFor).distinct().filter { isPageComplete(it, confirmed) }

    private fun readingOrder(paragraphs: List<PdfParagraph>): List<Item> {
        val weights = mutableMapOf<Long, Int>()
        for (paragraph in paragraphs) {
            val size = paragraph.size.roundToLong()
            weights[size] = (weights[size] ?: 0) + paragraph.plain.length
        }
        val base = weights.maxByOrNull { it.value }?.key ?: return emptyList()
        var waitingForLead = true
        val items = paragraphs.map { paragraph ->
            val plain = normalizePdfText(paragraph.text.replace(pdfMarkTag, ""))
            val kind = when {
                plain.length in 1..80 && plain.last() !in headingEnd && (paragraph.bold || paragraph.size >= base * 1.2) -> {
                    waitingForLead = true
                    Kind.Heading
                }
                paragraph.lineDetails.size == 1 && plain.length <= 40 && plain.lastOrNull() !in shortEnd -> Kind.Control
                waitingForLead -> {
                    waitingForLead = false
                    Kind.Lead
                }
                else -> Kind.Body
            }
            Item(paragraph.text, kind)
        }
        return items.sortedBy { it.kind.tier }.distinctBy { it.text }
    }

    private fun pageBatches(items: List<Item>, slots: Int): List<List<Item>> {
        val body = chunk(items.filter { it.kind != Kind.Control }).toMutableList()
        val short = chunk(items.filter { it.kind == Kind.Control })
        while (body.size + short.size < slots) {
            val longest = body.indices.filter { body[it].size > 1 }.maxByOrNull { body[it].size } ?: break
            val part = body.removeAt(longest)
            val middle = (part.size + 1) / 2
            body.add(longest, part.subList(0, middle))
            body.add(longest + 1, part.subList(middle, part.size))
        }
        return body + short
    }

    private fun chunk(items: List<Item>): List<List<Item>> = buildList {
        var part = mutableListOf<Item>()
        var chars = 0
        for (item in items) {
            if (part.isNotEmpty() && (part.size >= SequentialCore.MAX_LINES || chars + item.text.length > SequentialCore.MAX_CHARS)) {
                add(part)
                part = mutableListOf()
                chars = 0
            }
            part += item
            chars += item.text.length
        }
        if (part.isNotEmpty()) add(part)
    }

    private companion object {
        val headingEnd = ".!?。！？:：;；,、".toSet()
        val shortEnd = ".!?。！？;；".toSet()

        fun distance(page: Int, focus: Int): Double =
            if (page >= focus) (page - focus).toDouble() else (focus - page) * 1.5
    }
}
