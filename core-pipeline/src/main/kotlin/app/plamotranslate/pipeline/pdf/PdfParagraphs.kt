package app.plamotranslate.pipeline.pdf

import kotlin.math.abs
import kotlin.math.roundToLong

/** PDFium の文字の順序を基にセグメントを作り、座標と書式で横書きの段落にまとめる。 */
object PdfParagraphs {
    private val boldFont = Regex("bold|black|heavy", RegexOption.IGNORE_CASE)
    private val bullet = Regex("""^([•–·◦▪])$pdfWhitespace*""")
    private val leader = Regex("""^(.*?[^$pdfWhitespaceInner])$pdfWhitespace*((?:\.$pdfWhitespace){3,}\.?$pdfWhitespace*)$""")
    private val letter = Regex("""\p{L}""")
    private val words = Regex("""\p{L}{2,}""")
    private val japanese = Regex("""[\p{IsHiragana}\p{IsKatakana}]""")
    private val digits = Regex("[0-9]+")

    private class TextSegment(
        val glyphs: MutableList<PdfGlyph>,
        val baseline: Double,
        var size: Double,
    )

    private class Line(val glyphs: List<PdfGlyph>, val baseline: Double, val size: Double) {
        val bounds = glyphs.asSequence().filter { it.text != " " }.map { it.bounds }.reduce(PdfBounds::union)
        val bold = glyphs.count { it.text != " " && it.isBold() } * 2 > glyphs.count { it.text != " " }
        val bullet = PdfParagraphs.bullet.find(glyphs.joinToString("") { it.text })?.value?.length ?: 0
        val x = if (bullet == 0) bounds.left else glyphs.drop(bullet).firstOrNull { it.text != " " }?.bounds?.left ?: bounds.left
        val range = glyphs.first().index..glyphs.last().index
    }

    fun page(input: PdfPageText): List<PdfParagraph> {
        val lines = segments(input.glyphs).sortedWith(compareByDescending<Line> { it.baseline }.thenBy { it.bounds.left })
        val pageRight = lines.maxOfOrNull { it.bounds.right }?.coerceAtLeast(0.0) ?: return emptyList()
        val neighbors = lines.mapIndexed { index, line ->
            val tolerance = 0.4 * line.size
            val from = (index - 1 downTo 0).firstOrNull { lines[it].baseline - line.baseline >= tolerance }?.plus(1) ?: 0
            val until = (index + 1 until lines.size).firstOrNull { line.baseline - lines[it].baseline >= tolerance } ?: lines.size
            lines.subList(from, until).filter { it !== line }
        }
        val groups = mutableListOf<MutableList<Int>>()
        for ((index, line) in lines.withIndex()) {
            val group = groups.lastOrNull()
            val previous = group?.last()?.let(lines::get)
            val gap = (previous?.baseline ?: line.baseline) - line.baseline
            if (group != null && previous != null && line.bullet == 0 && neighbors[index].isEmpty() &&
                abs(line.size - previous.size) < 0.6 && line.bold == previous.bold &&
                gap > 0.8 * line.size && gap < 1.7 * line.size &&
                abs(line.bounds.left - lines[group.first()].x) < 3
            ) {
                group += index
            } else {
                groups += mutableListOf(index)
            }
        }
        return groups.map { group ->
            paragraph(input.size, group.map(lines::get), neighbors[group.first()], pageRight)
        }
    }

    /** 同じ丸め済み基線と原文（数字を除く）が異なる3ページに現れるときだけ繰り返しとする。 */
    fun classify(pages: Map<Int, List<PdfParagraph>>): Map<Int, List<PdfParagraph>> {
        fun key(p: PdfParagraph) = p.baseline.roundToLong() to p.plain.replace(digits, "#")
        val occurrences = mutableMapOf<Pair<Long, String>, MutableSet<Int>>()
        pages.forEach { (page, paragraphs) ->
            paragraphs.forEach { occurrences.getOrPut(key(it)) { mutableSetOf() }.add(page) }
        }
        return pages.mapValues { (_, paragraphs) ->
            paragraphs.map { p ->
                val skip = when {
                    occurrences.getValue(key(p)).size >= 3 -> PdfSkip.Repeat
                    !words.containsMatchIn(p.plain) -> PdfSkip.NoWords
                    japanese.containsMatchIn(p.plain) -> PdfSkip.Japanese
                    else -> null
                }
                p.copy(skip = skip)
            }
        }
    }

    private fun segments(glyphs: List<PdfGlyph>): List<Line> {
        val segments = mutableListOf<TextSegment>()
        var active: TextSegment? = null
        var last: PdfGlyph? = null
        for (glyph in glyphs) {
            if (glyph.text in listOf("\r", "\n", "\u0000") || (glyph.generated && glyph.text != " ")) continue
            if (glyph.text == " ") {
                active?.glyphs?.add(glyph)
                continue
            }
            val s = active
            val left = glyph.bounds.left
            val previousRight = last?.bounds?.right ?: left
            val superscript = s != null && glyph.size < 0.8 * s.size &&
                abs(glyph.originY - s.baseline) < 0.6 * s.size &&
                left > previousRight - s.size && left < previousRight + s.size
            if (s == null || (!superscript && (abs(glyph.originY - s.baseline) > 0.4 * glyph.size ||
                        left < previousRight - glyph.size || left - previousRight > maxOf(glyph.size, s.size)))) {
                active = TextSegment(mutableListOf(), glyph.originY, glyph.size).also(segments::add)
            }
            val current = checkNotNull(active)
            current.glyphs += glyph
            if (!superscript) current.size = maxOf(current.size, glyph.size)
            last = glyph
        }
        return buildList {
            for (segment in segments) {
                val raw = segment.glyphs.joinToString("") { it.text }
                val title = leader.matchEntire(raw)?.groupValues?.get(1)
                val boundary = if (title != null && letter.containsMatchIn(title)) title.codePointCount(0, title.length) else raw.codePointCount(0, raw.length)
                fun addLine(part: List<PdfGlyph>) {
                    val trimmed = part.dropWhile { it.text == " " }.dropLastWhile { it.text == " " }
                    if (trimmed.isNotEmpty()) add(Line(trimmed, segment.baseline, segment.size))
                }
                addLine(segment.glyphs.take(boundary))
                addLine(segment.glyphs.drop(boundary))
            }
        }
    }

    private fun paragraph(pageSize: PdfPageSize, lines: List<Line>, sameLine: List<Line>, pageRight: Double): PdfParagraph {
        val first = lines.first()
        val last = lines.last()
        val body = buildList {
            lines.forEachIndexed { index, line ->
                val glyphs = if (index == 0) line.glyphs.drop(first.bullet) else line.glyphs
                val hyphen = glyphs.lastOrNull()?.hyphen == true
                addAll(if (hyphen) glyphs.dropLast(1) else glyphs)
                if (index < lines.lastIndex && !hyphen) add(null)
            }
        }
        val translated = body.mapNotNull { glyph -> glyph?.takeUnless { it.generated || it.text == " " }?.index }.toSet()
        val markup = encodePdfMarkup(body, first.bold)
        val next = sameLine.asSequence().map { it.bounds.left }.filter { it > first.bounds.right }.minOrNull()
        val right = when {
            lines.size > 1 -> lines.maxOf { it.bounds.right }
            next != null -> next - 4
            else -> pageRight
        }
        return PdfParagraph(
            text = markup.text,
            plain = markup.plain,
            pageSize = pageSize,
            lineDetails = lines.map { PdfParagraphLine(it.bounds, it.baseline, it.range) },
            glyphs = lines.flatMap { line ->
                line.glyphs.filterNot { it.generated || it.text == " " }.map { PdfParagraphGlyph(it, it.index in translated) }
            },
            x = first.x,
            right = maxOf(right, first.bounds.right),
            baseline = first.baseline,
            bottomBaseline = last.baseline,
            leading = if (lines.size > 1) (first.baseline - last.baseline) / (lines.size - 1) else first.size * 1.25,
            size = first.size,
            bold = first.bold,
            color = body.firstOrNull { it != null && it.index in translated }?.color ?: PdfColor.Black,
            elements = markup.elements,
            whole = markup.whole,
        )
    }

    internal fun PdfGlyph.isBold() = weight >= 600 || boldFont.containsMatchIn(font)
}
