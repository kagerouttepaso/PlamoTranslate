package app.plamotranslate.pipeline.pdf

import java.text.BreakIterator
import java.util.Locale

data class PdfPreviewLine(val text: String, val sentences: Int, val complete: Boolean)

/** 生成途中の文は文末まで、モデルが改行した段落は1行分を表示する。 */
object PdfPreview {
    private val unfinishedTag = Regex("""</?(?:[acv][0-9]*/?)?$""")
    private val terminal = Regex("""[.!?。！？｡։؟۔।॥][\p{Pe}\p{Pf}"']*$""")
    private val letter = Regex("""\p{L}""")

    fun text(line: String, complete: Boolean): String = line(line, complete).text

    fun line(line: String, complete: Boolean): PdfPreviewLine {
        val prose = StringBuilder()
        val offsets = mutableListOf<Int>()
        var cursor = 0
        fun append(end: Int) {
            prose.append(line, cursor, end)
            while (cursor < end) offsets += ++cursor
        }
        fun finish(end: Int): PdfPreviewLine {
            if (complete) return PdfPreviewLine(line.substring(0, end).trim(), 0, true)
            val ends = sentenceEnds(prose.toString())
            val text = ends.lastOrNull()?.let { line.substring(0, offsets[it - 1]).trim() }.orEmpty()
            return PdfPreviewLine(text, ends.size, false)
        }
        for (match in pdfMarkTag.findAll(line)) {
            val start = match.range.first
            if (start < cursor) continue
            append(start)
            val (_, closing, kind, id) = match.groupValues
            cursor = match.range.last + 1
            if (closing.isEmpty() && kind == "c") {
                val close = "</c$id>"
                val end = line.indexOf(close, cursor)
                if (end == -1) return finish(start)
                cursor = end + close.length
                prose.append('\uFFFC')
                offsets += cursor
            } else if (closing.isNotEmpty() && offsets.lastOrNull() == start) {
                offsets[offsets.lastIndex] = cursor
            }
        }
        val end = unfinishedTag.find(line)?.range?.first?.takeIf { it >= cursor } ?: line.length
        append(end)
        return finish(end)
    }

    internal fun sentenceEnds(text: String): List<Int> = buildList {
        val iterator = BreakIterator.getSentenceInstance(Locale.ROOT)
        iterator.setText(text)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = text.substring(start, end).trimEnd()
            if (letter.containsMatchIn(sentence) && terminal.containsMatchIn(sentence)) add(start + sentence.length)
            start = end
            end = iterator.next()
        }
    }
}
