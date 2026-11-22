package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfColor
import app.plamotranslate.pipeline.pdf.PdfLayout
import app.plamotranslate.pipeline.pdf.PdfMarkupTranslation
import app.plamotranslate.pipeline.pdf.PdfMeasure
import app.plamotranslate.pipeline.pdf.PdfParagraph
import kotlin.math.abs
import kotlin.math.roundToLong

data class PdfWriteResult(val translated: Int, val masked: Int, val movedLinks: Int)

internal sealed interface PdfContent {
    data class Mask(val box: PdfBounds, val color: PdfColor) : PdfContent
    data class Text(val text: String, val x: Double, val baseline: Double, val size: Double, val bold: Boolean,
                    val color: PdfColor) : PdfContent
}

internal data class PageEdit(
    val remove: Set<Int>,
    val retain: Set<Int>,
    val content: List<PdfContent>,
    val moves: Map<Int, List<PdfBounds>>,
    val duplicateLinks: List<Int>,
    val result: PdfWriteResult,
) {
    fun apply(document: Long, page: Int): Int {
        val strings = arrayOfNulls<String>(content.size)
        val coordinates = DoubleArray(content.size * 5)
        for ((index, item) in content.withIndex()) {
            val fields = when (item) {
                is PdfContent.Mask -> item.box.fields() + item.color.argb()
                is PdfContent.Text -> {
                    require(item.text.isNotEmpty() && '\u0000' !in item.text)
                    strings[index] = item.text
                    doubleArrayOf(item.x, item.baseline, item.size, if (item.bold) 1.0 else 0.0, item.color.argb())
                }
            }
            require(fields.all(Double::isFinite))
            fields.copyInto(coordinates, index * 5)
        }
        return PdfEditingNative.writePage(
            document, page, remove.toIntArray(), retain.sorted().toIntArray(), strings, coordinates,
            moves.flatMap { (index, boxes) -> listOf(index, boxes.size) }.toIntArray(),
            moves.values.flatten().flatMap { it.fields().asIterable() }.toDoubleArray(),
            duplicateLinks.sortedDescending().toIntArray(),
        )
    }
}

private fun PdfColor.argb(): Double {
    require(red in 0..255 && green in 0..255 && blue in 0..255)
    return (0xff000000.toInt() or (red shl 16) or (green shl 8) or blue).toDouble()
}

private fun PdfBounds.fields() = doubleArrayOf(left, bottom, right, top)

internal fun pageEdit(
    paragraphs: List<PdfParagraph>,
    translations: Map<String, String>,
    measure: PdfMeasure,
    links: List<PdfLink>,
    background: (List<PdfParagraph>) -> Map<PdfParagraph, PdfColor>,
): PageEdit? {
    val prepared = paragraphs.mapNotNull { paragraph ->
        val translation = translations[paragraph.text] ?: return@mapNotNull null
        val pieces = PdfMarkupTranslation.prepare(paragraph, translation) ?: return@mapNotNull null
        val geometry = PdfLayout.paragraphGeometry(paragraph, paragraphs)
        val layout = PdfLayout.planParagraph(geometry, pieces, measure)
        if (layout.lines.isEmpty()) return@mapNotNull null
        Triple(paragraph, geometry, layout)
    }
    if (prepared.isEmpty()) return null

    val replacing = prepared.map { it.first }
    val removing = replacing.flatMap { it.objectPaths }.mapNotNull { it.singleOrNull() }.toSet()
    val translated = replacing.flatMap { it.glyphs }.filter { it.translated }.map { it.glyph.index }.toSet()
    val retained = paragraphs.asSequence().flatMap { it.glyphs }.map { it.glyph }.filter {
        !it.generated && it.text != " " && it.index !in translated && it.objectPath?.singleOrNull() in removing
    }.map { it.index }.toSet()
    val masked = prepared.filter { it.second.masked }.map { it.first }
    val colors = background(masked)

    val runsByLink = mutableMapOf<Int, MutableList<PdfBounds>>()
    val contents = buildList {
        for ((paragraph, geometry, layout) in prepared) {
            if (geometry.masked) {
                val box = geometry.bounds
                val padded = PdfBounds(box.left - 1, box.bottom - 1, box.right + 1, box.top + 1)
                add(PdfContent.Mask(padded, colors.getValue(paragraph)))
            }
            for (line in layout.lines) {
                for (run in line.runs) {
                    add(PdfContent.Text(run.text, run.x, line.baseline, layout.size, run.bold, paragraph.color))
                    run.link?.let { index ->
                        runsByLink.getOrPut(index, ::mutableListOf).add(PdfBounds(
                            run.x, line.baseline - 0.25 * layout.size,
                            run.x + run.width, line.baseline + 0.95 * layout.size,
                        ))
                    }
                }
            }
        }
    }
    val (moves, duplicates) = linkChanges(links, runsByLink, prepared.map { it.second.bounds })
    return PageEdit(removing, retained, contents, moves, duplicates,
        PdfWriteResult(prepared.size, masked.size, moves.size))
}

private data class PageTarget(val page: Int, val xTenths: Long?, val yTenths: Long?)

private fun Double.tenths(): Long = (abs(this) * 10).roundToLong().let { if (this < 0) -it else it }

private fun PdfLink.key(): Any = when (val target = destination) {
    is PdfDestination.Page -> PageTarget(target.index, target.x?.tenths(), target.y?.tenths())
    is PdfDestination.Uri -> target
    null -> index
}

private fun linkChanges(
    links: List<PdfLink>, runs: Map<Int, List<PdfBounds>>, originals: List<PdfBounds>,
): Pair<Map<Int, List<PdfBounds>>, List<Int>> {
    val byIndex = links.associateBy { it.index }
    val rectangles = mutableMapOf<Any, MutableList<PdfBounds>>()
    for ((index, boxes) in runs) {
        val link = byIndex[index] ?: continue
        val lines = rectangles.getOrPut(link.key(), ::mutableListOf)
        for (box in boxes) {
            val sameLine = lines.indexOfFirst { abs(it.bottom - box.bottom) < .5 && abs(it.top - box.top) < .5 }
            if (sameLine < 0) {
                lines += box
            } else {
                val old = lines[sameLine]
                lines[sameLine] = old.copy(left = minOf(old.left, box.left), right = maxOf(old.right, box.right))
            }
        }
    }
    val groups = links.filter { link ->
        val x = (link.bounds.left + link.bounds.right) / 2
        val y = (link.bounds.bottom + link.bounds.top) / 2
        link.key() in rectangles && (link.index in runs ||
            originals.any { x >= it.left - 1 && x <= it.right + 1 && y >= it.bottom - 1 && y <= it.top + 1 })
    }.groupBy { it.key() }
    val moves = mutableMapOf<Int, List<PdfBounds>>()
    val duplicates = mutableListOf<Int>()
    for ((key, group) in groups) {
        val sorted = group.sortedBy { it.index }
        moves[sorted.first().index] = rectangles.getValue(key)
        duplicates += sorted.drop(1).map { it.index }
    }
    return moves to duplicates
}
