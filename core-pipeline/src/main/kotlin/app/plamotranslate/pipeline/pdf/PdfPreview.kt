package app.plamotranslate.pipeline.pdf

import java.text.BreakIterator
import java.util.Locale

data class PdfPreviewLine(val text: String, val sentences: Int, val complete: Boolean)

/** 生成途中の文は文末まで、モデルが改行した段落は1行分を表示する。 */
object PdfPreview {
    private val unfinishedTag = Regex("""</?(?:[acv][0-9]*/?)?$""")
    // Unicode の Sentence_Terminal。JVM の正規表現ではこの二値プロパティを指定できない。
    private val sentenceTerminal = Regex(
        """[\x{21}\x{2e}\x{3f}\x{589}\x{61d}-\x{61f}\x{6d4}\x{700}-\x{702}\x{7f9}\x{837}\x{839}""" +
        """\x{83d}-\x{83e}\x{964}-\x{965}\x{104a}-\x{104b}\x{1362}\x{1367}-\x{1368}\x{166e}""" +
        """\x{1735}-\x{1736}\x{17d4}-\x{17d5}\x{1803}\x{1809}\x{1944}-\x{1945}\x{1aa8}-\x{1aab}""" +
        """\x{1b4e}-\x{1b4f}\x{1b5a}-\x{1b5b}\x{1b5e}-\x{1b5f}\x{1b7d}-\x{1b7f}\x{1c3b}-\x{1c3c}""" +
        """\x{1c7e}-\x{1c7f}\x{2024}\x{203c}-\x{203d}\x{2047}-\x{2049}\x{2cf9}-\x{2cfb}\x{2e2e}\x{2e3c}""" +
        """\x{2e53}-\x{2e54}\x{3002}\x{a4ff}\x{a60e}-\x{a60f}\x{a6f3}\x{a6f7}\x{a876}-\x{a877}""" +
        """\x{a8ce}-\x{a8cf}\x{a92f}\x{a9c8}-\x{a9c9}\x{aa5d}-\x{aa5f}\x{aaf0}-\x{aaf1}\x{abeb}\x{fe12}""" +
        """\x{fe15}-\x{fe16}\x{fe52}\x{fe56}-\x{fe57}\x{ff01}\x{ff0e}\x{ff1f}\x{ff61}\x{10a56}-\x{10a57}""" +
        """\x{10f55}-\x{10f59}\x{10f86}-\x{10f89}\x{11047}-\x{11048}\x{110be}-\x{110c1}\x{11141}-\x{11143}""" +
        """\x{111c5}-\x{111c6}\x{111cd}\x{111de}-\x{111df}\x{11238}-\x{11239}\x{1123b}-\x{1123c}\x{112a9}""" +
        """\x{113d4}-\x{113d5}\x{1144b}-\x{1144c}\x{115c2}-\x{115c3}\x{115c9}-\x{115d7}\x{11641}-\x{11642}""" +
        """\x{1173c}-\x{1173e}\x{11944}\x{11946}\x{11a42}-\x{11a43}\x{11a9b}-\x{11a9c}\x{11c41}-\x{11c42}""" +
        """\x{11ef7}-\x{11ef8}\x{11f43}-\x{11f44}\x{16a6e}-\x{16a6f}\x{16af5}\x{16b37}-\x{16b38}\x{16b44}""" +
        """\x{16d6e}-\x{16d6f}\x{16e98}\x{1bc9f}\x{1da88}]""",
    )
    private val terminal = Regex("""[.!?。][\p{Pe}\p{Pf}"\x27]*$""")
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
        // 一部の文字体系の句読点を JDK が文末として扱わない。境界の計算だけ和文句点にそろえ、
        // 小数点と引用符は元の形、UTF-16 の位置は同じに保つ。
        val normalized = sentenceTerminal.replace(text) { match ->
            when (match.value) {
                ".", "!", "?", "。" -> match.value
                "．", "﹒", "․" -> "."
                else -> if (match.value.length == 1) "。" else " 。"
            }
        }
        iterator.setText(normalized)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            val sentence = normalized.substring(start, end).trimEnd()
            if (letter.containsMatchIn(sentence) && terminal.containsMatchIn(sentence)) add(start + sentence.length)
            start = end
            end = iterator.next()
        }
    }
}

/** 1か所の途中表示。同じ原文を共有しても、段落ごとの文字色・太字・位置を使う。 */
data class PdfMark(val index: Int, val paragraph: PdfParagraph, val geometry: PdfParagraphGeometry, val pieces: List<PdfPiece>)

fun PdfState.pageMarks(page: Int): List<PdfMark> {
    val paragraphs = layouts[page] ?: return emptyList()
    if (page in writeFailed) return emptyList()
    return paragraphs.mapIndexedNotNull { index, paragraph ->
        // 元の字形を使う段落は PDFium が書き終えるまで原文で見せる。
        if (paragraph.skip != null || paragraph.elements.any { it.original != null }) return@mapIndexedNotNull null
        val final = translations[page]?.get(index)
        val pieces = when {
            final != null && paragraph.text in confirmedMarks -> PdfMarkupTranslation.pieces(paragraph, final)
            paragraph.text in working -> previews[paragraph.text]?.let { PdfMarkupTranslation.previewPieces(paragraph, it) }.orEmpty()
            else -> return@mapIndexedNotNull null
        }
        PdfMark(index, paragraph, PdfLayout.paragraphGeometry(paragraph, paragraphs), pieces)
    }
}
