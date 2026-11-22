package app.plamotranslate.pipeline.pdf

data class PdfPiece(val text: String, val bold: Boolean = false, val link: Int? = null, val source: Boolean = false)

data class PdfRun(val text: String, val width: Double, val x: Double, val bold: Boolean, val link: Int?, val source: Boolean)

data class PdfPlacedLine(val baseline: Double, val runs: List<PdfRun>)

data class PdfPlacement(val size: Double, val leading: Double, val shrunk: Boolean, val lines: List<PdfPlacedLine>)

data class PdfParagraphGeometry(
    val masked: Boolean,
    val bounds: PdfBounds,
    val x: Double,
    val baseline: Double,
    val width: Double,
    val floor: Double,
    val size: Double,
    val leading: Double,
)

typealias PdfMeasure = (text: String, bold: Boolean, size: Double) -> Double

/** 途中表示と PDF 書き込みで使う配置。位置と字幅は PDF のポイント。 */
object PdfLayout {
    private val noStart = Regex("""^[、。，．・：；？！ー）」』】〕”’ぁぃぅぇぉっゃゅょゎァィゥェォッャュョヮヵヶ々.,:;!?)\]]""")
    private val noEnd = Regex("""[（「『【〔“‘(\[]$""")
    private val atom = Regex("""[\x21-\x7e -ɏ–’µ®°×]+|$pdfWhitespace+|.""")
    private val whitespace = Regex("$pdfWhitespace+")

    private data class Atom(val text: String, val width: Double, val piece: PdfPiece)

    fun paragraphGeometry(paragraph: PdfParagraph, page: List<PdfParagraph>): PdfParagraphGeometry {
        val masked = paragraph.objectPaths.any { it.size > 1 }
        val box = paragraph.translationBounds ?: paragraph.bounds
        val below = page.asSequence().filter { other ->
            other !== paragraph && other.baseline < paragraph.bottomBaseline - 1 &&
                other.x < paragraph.right && other.right > paragraph.x
        }.maxByOrNull { it.baseline }
        val floor = if (below == null) paragraph.bottomBaseline
            else minOf(paragraph.bottomBaseline, below.baseline + below.size * 1.15)
        val width = if (!masked) paragraph.right - paragraph.x
            else (box.right - paragraph.x) * if (paragraph.lineDetails.size > 1) 1.0 else 1.15
        return PdfParagraphGeometry(masked, box, paragraph.x, paragraph.baseline, width + 0.5, floor - 0.5,
            paragraph.size, paragraph.leading)
    }

    fun planParagraph(geometry: PdfParagraphGeometry, pieces: List<PdfPiece>, measure: PdfMeasure): PdfPlacement {
        val translation = pieces.filterNot { it.source }
        var step = 0
        var size: Double
        var leading: Double
        var lines: List<List<Atom>>
        while (true) {
            val scale = 1 - step * 0.05
            size = geometry.size * scale
            leading = geometry.leading * scale
            lines = wrap(translation, geometry.width, size, measure)
            if (geometry.baseline - (lines.size - 1) * leading >= geometry.floor || step == 6) break
            step++
        }
        if (pieces.size != translation.size) lines = wrap(pieces, geometry.width, size, measure)
        return PdfPlacement(size, leading, step > 0, lines.mapIndexedNotNull { index, line ->
            val baseline = geometry.baseline - index * leading
            var x = geometry.x
            val runs = mutableListOf<PdfRun>()
            for (a in line) {
                val (bold, link, source) = a.piece.let { Triple(it.bold, it.link, it.source) }
                if (source && (baseline < geometry.floor || x + a.width > geometry.x + geometry.width)) break
                val last = runs.lastOrNull()
                if (last != null && last.bold == bold && last.link == link && last.source == source) {
                    runs[runs.lastIndex] = last.copy(text = last.text + a.text, width = last.width + a.width)
                } else {
                    runs += PdfRun(a.text, a.width, x, bold, link, source)
                }
                x += a.width
            }
            if (runs.isEmpty()) null else PdfPlacedLine(baseline, runs)
        })
    }

    private fun wrap(pieces: List<PdfPiece>, width: Double, size: Double, measure: PdfMeasure): List<List<Atom>> {
        val atoms = pieces.asSequence().flatMap { piece ->
            atom.findAll(piece.text).map {
                val text = if (whitespace.matches(it.value)) " " else it.value
                Atom(text, measure(text, piece.bold, size), piece)
            }
        }
        val lines = mutableListOf(mutableListOf<Atom>())
        var used = 0.0
        for (a in atoms) {
            val line = lines.last()
            if (a.text == " " && line.isEmpty()) continue
            if (used + a.width > width && line.isNotEmpty()) {
                if (a.text == " ") {
                    lines.add(mutableListOf())
                    used = 0.0
                    continue
                }
                if (a.text.length <= 2 && noStart.containsMatchIn(a.text) && line.last().piece.source == a.piece.source) {
                    line += a
                    used += a.width
                    continue
                }
                val carry = ArrayDeque<Atom>()
                while (line.size > 1 && line.last().piece.source == a.piece.source && noEnd.containsMatchIn(line.last().text)) {
                    carry.addFirst(line.removeLast())
                }
                while (line.lastOrNull()?.text == " ") line.removeLast()
                lines += (carry + a).toMutableList()
                used = carry.sumOf { it.width } + a.width
                continue
            }
            line += a
            used += a.width
        }
        return lines.map { it.dropLastWhile { a -> a.text == " " } }.filter { it.isNotEmpty() }
    }
}
