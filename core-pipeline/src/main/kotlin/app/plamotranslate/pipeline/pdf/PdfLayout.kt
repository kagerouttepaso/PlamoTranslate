package app.plamotranslate.pipeline.pdf

import app.plamotranslate.pipeline.layout.TextGrouper
import kotlin.math.abs

data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerY get() = (top + bottom) / 2

    fun union(other: PdfRect) = PdfRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
    )
}

data class PdfGlyph(val text: String, val box: PdfRect, val spaceBefore: Boolean = false)

data class PdfParagraph(val text: String, val lines: List<PdfRect>) {
    val box = lines.reduce(PdfRect::union)
}

object PdfLayout {
    private data class Line(val text: String, val box: PdfRect)
    private class GlyphRow(val center: Float, val height: Float, val glyphs: MutableList<PdfGlyph>)
    private val caption = Regex("^(?:fig(?:ure)?\\.?|table)\\s+\\d+\\s*[:：]", RegexOption.IGNORE_CASE)

    fun paragraphs(glyphs: List<PdfGlyph>, pageAspect: Float): List<PdfParagraph> {
        require(pageAspect.isFinite() && pageAspect > 0)
        val visible = glyphs.filter { it.text.isNotBlank() && it.box.width > 0 && it.box.height > 0 }.distinct()
        if (visible.isEmpty()) return emptyList()
        val typical = visible.map { it.box.height }.sorted()[visible.size / 2]
        val rows = mutableListOf<GlyphRow>()
        val (letters, marks) = visible.partition { it.text.any(Char::isLetterOrDigit) }
        for (glyph in letters.sortedBy { it.box.centerY }) {
            val box = glyph.box
            val row = rows.lastOrNull()?.takeIf {
                abs(it.center - box.centerY) <= minOf(it.height.coerceAtLeast(typical), box.height.coerceAtLeast(typical)) * 0.52f
            }
            if (row == null) rows += GlyphRow(box.centerY, box.height, mutableListOf(glyph)) else row.glyphs += glyph
        }
        for (glyph in marks) {
            val box = glyph.box
            val row = rows.minByOrNull { abs(it.center - box.centerY) }?.takeIf {
                abs(it.center - box.centerY) <= it.height.coerceAtLeast(typical) * 0.75f
            }
            if (row == null) rows += GlyphRow(box.centerY, box.height, mutableListOf(glyph)) else row.glyphs += glyph
        }
        return readRows(rows.sortedBy { it.center }.map { row ->
            val heights = row.glyphs.filter { it.text.any(Char::isLetterOrDigit) }.map { it.box.height }.sorted()
            spans(row.glyphs, (heights.getOrNull(heights.size / 2) ?: typical) / pageAspect)
        })
    }

    private fun spans(glyphs: List<PdfGlyph>, letterHeight: Float): List<Line> {
        val ordered = glyphs.sortedBy { it.box.left }
        val output = mutableListOf<Line>()
        var box = ordered.first().box
        val text = StringBuilder()
        for (glyph in ordered) {
            val gap = glyph.box.left - box.right
            if (text.isNotEmpty() && gap > letterHeight * 2.3f) {
                output += Line(text.toString(), box)
                text.clear()
                box = glyph.box
            } else if (text.isNotEmpty() && (gap > letterHeight * 0.40f ||
                    (glyph.spaceBefore && gap > letterHeight * 0.12f))) {
                text.append(' ')
            }
            box = box.union(glyph.box)
            text.append(glyph.text)
        }
        output += Line(text.toString(), box)
        return output
    }

    private fun readRows(rows: List<List<Line>>): List<PdfParagraph> {
        if (rows.isEmpty()) return emptyList()
        val gutter = columnGutter(rows) ?: return readSingleColumn(rows)
        val output = mutableListOf<PdfParagraph>()
        val left = mutableListOf<List<Line>>()
        val right = mutableListOf<List<Line>>()
        val spanning = mutableListOf<List<Line>>()
        fun flushColumns() {
            output += readRows(left.toList())
            output += readRows(right.toList())
            left.clear()
            right.clear()
        }
        for (row in rows) {
            if (row.any { it.box.left < gutter && it.box.right > gutter }) {
                flushColumns()
                spanning += row
            } else {
                output += readSingleColumn(spanning)
                spanning.clear()
                row.filter { it.box.right <= gutter }.takeIf { it.isNotEmpty() }?.let(left::add)
                row.filter { it.box.left >= gutter }.takeIf { it.isNotEmpty() }?.let(right::add)
            }
        }
        output += readSingleColumn(spanning)
        flushColumns()
        return output
    }

    private fun columnGutter(rows: List<List<Line>>): Float? {
        val pairs = rows.mapNotNull { row ->
            if (row.size != 2 || row.any { it.text.count(Char::isLetter) < 18 }) null else row[0] to row[1]
        }
        return pairs.map { (left, right) -> (left.box.right + right.box.left) / 2 }
            .map { gap -> gap to pairs.count { (left, right) -> gap > left.box.right && gap < right.box.left } }
            .filter { (_, support) -> support >= 2 }
            .maxByOrNull { (_, support) -> support }?.first
    }

    private fun readSingleColumn(rows: List<List<Line>>): List<PdfParagraph> {
        val output = mutableListOf<PdfParagraph>()
        val paragraph = mutableListOf<Line>()
        fun flush() {
            if (paragraph.isNotEmpty()) {
                output += PdfParagraph(paragraph.joinToString(" ") { it.text }, paragraph.map { it.box })
                paragraph.clear()
            }
        }
        for (row in rows) {
            if (row.size > 1) {
                flush()
                output += row.map { PdfParagraph(it.text, listOf(it.box)) }
                continue
            }
            val line = row.single()
            val previous = paragraph.lastOrNull()
            if (previous != null && !continues(previous, line)) flush()
            paragraph += line
        }
        flush()
        return output
    }

    private fun continues(previous: Line, next: Line): Boolean {
        val upper = previous.box
        val lower = next.box
        return !caption.containsMatchIn(next.text) && !TextGrouper.ITEM_START.containsMatchIn(next.text) &&
            lower.top - upper.bottom <= maxOf(upper.height, lower.height) &&
            maxOf(upper.height, lower.height) <= minOf(upper.height, lower.height) * 1.45f &&
            minOf(upper.right, lower.right) > maxOf(upper.left, lower.left)
    }
}
