package app.plamotranslate.pipeline.pdf

/** 単位は PDF のポイント。原点はページ左下、Y 軸は上向き。 */
data class PdfPageSize(val width: Double, val height: Double) {
    init {
        require(width.isFinite() && width > 0 && height.isFinite() && height > 0)
    }
}

data class PdfBounds(val left: Double, val bottom: Double, val right: Double, val top: Double) {
    val width get() = right - left
    val height get() = top - bottom

    fun union(other: PdfBounds) = PdfBounds(
        minOf(left, other.left), minOf(bottom, other.bottom), maxOf(right, other.right), maxOf(top, other.top),
    )
}

data class PdfColor(val red: Int, val green: Int, val blue: Int) {
    companion object {
        val Black = PdfColor(0, 0, 0)
        val White = PdfColor(255, 255, 255)
    }
}

/**
 * [index] は PDFium テキストページの文字番号。[text] は Unicode の1コードポイント。
 * [objectPath] はページからテキストオブジェクトへたどる0始まりの番号列。フォームの中では親の番号から順に並ぶ。
 * [size] は Tf に FPDFText_GetMatrix の縦の拡大率 hypot(c, d) を掛けたポイント数。
 * [link] は文字の中心を含むリンク注釈の番号。
 */
data class PdfGlyph(
    val index: Int,
    val text: String,
    val originX: Double,
    val originY: Double,
    val bounds: PdfBounds,
    val font: String,
    val weight: Int,
    val color: PdfColor,
    val objectPath: List<Int>?,
    val size: Double,
    val generated: Boolean = false,
    val hyphen: Boolean = false,
    val link: Int? = null,
)

/** [glyphs] は PDFium テキストページの文字番号順。 */
data class PdfPageText(val size: PdfPageSize, val glyphs: List<PdfGlyph>)

data class PdfLinkAnnotation(val index: Int, val bounds: PdfBounds)

/** PDFium と同じ注釈順で、外接矩形の中心を含む最初のリンクを選ぶ。 */
fun pdfGlyphLink(bounds: PdfBounds, links: List<PdfLinkAnnotation>): Int? {
    val x = (bounds.left + bounds.right) / 2
    val y = (bounds.bottom + bounds.top) / 2
    return links.firstOrNull { (_, box) ->
        x >= box.left - 0.5 && x <= box.right + 0.5 && y >= box.bottom - 0.5 && y <= box.top + 0.5
    }?.index
}

enum class PdfSkip { Repeat, NoWords, Japanese }

enum class PdfElementKind { Decoration, Code }

/** [bold] は段落の太字を反転する指定。コードの文字列は原文を置き直すために保持する。 */
data class PdfElement(val id: Int, val kind: PdfElementKind, val link: Int?, val bold: Boolean, val code: String = "")

data class PdfParagraphGlyph(val glyph: PdfGlyph, val translated: Boolean)

data class PdfParagraphLine(val bounds: PdfBounds, val baseline: Double, val characterRange: IntRange)

data class PdfParagraph(
    val text: String,
    val plain: String,
    val pageSize: PdfPageSize,
    val lineDetails: List<PdfParagraphLine>,
    val glyphs: List<PdfParagraphGlyph>,
    val x: Double,
    val right: Double,
    val baseline: Double,
    val bottomBaseline: Double,
    val leading: Double,
    val size: Double,
    val bold: Boolean,
    val color: PdfColor,
    val elements: List<PdfElement> = emptyList(),
    val whole: PdfElement? = null,
    val skip: PdfSkip? = null,
) {
    val bounds = lineDetails.map { it.bounds }.reduce(PdfBounds::union)
    val characterRange = lineDetails.minOf { it.characterRange.first }..lineDetails.maxOf { it.characterRange.last }
    val translationBounds = glyphs.asSequence().filter { it.translated }.map { it.glyph.bounds }
        .reduceOrNull(PdfBounds::union)
    val objectPaths = glyphs.mapNotNull { if (it.translated) it.glyph.objectPath else null }.distinct()
    val keptGlyphs get() = glyphs.filter { skip != null || !it.translated }.map { it.glyph }

    // PDF の点座標を、現行の画面が使う左上原点の正規化座標へ変換する。
    val lines get() = lineDetails.map { line ->
        val box = line.bounds
        PdfRect(
            (box.left / pageSize.width).toFloat(), (1 - box.top / pageSize.height).toFloat(),
            (box.right / pageSize.width).toFloat(), (1 - box.bottom / pageSize.height).toFloat(),
        )
    }
    val box get() = lines.reduce(PdfRect::union)
}

/** 旧ビューアの左上原点の正規化座標。 */
data class PdfRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top

    fun union(other: PdfRect) = PdfRect(
        minOf(left, other.left), minOf(top, other.top), maxOf(right, other.right), maxOf(bottom, other.bottom),
    )
}
