package app.plamotranslate.pipeline.pdf

import kotlin.math.ceil
import kotlin.math.floor

/** 左上から行順に並んだ ARGB の画素。文字色に近い画素を除き、矩形内の最頻色を取る。 */
class PdfBackground(
    private val pixels: IntArray,
    private val width: Int,
    private val height: Int,
    pageSize: PdfPageSize,
) {
    init {
        require(width > 0 && height > 0 && pixels.size.toLong() == width.toLong() * height)
    }

    // PDFium の原寸描画は切り上げたページ寸法を使う。縮小・拡大した画像でも同じ範囲を取る。
    private val scaleX = width / ceil(pageSize.width)
    private val scaleY = height / ceil(pageSize.height)

    fun color(bounds: PdfBounds, text: PdfColor): PdfColor {
        val left = floor(bounds.left * scaleX).toInt().coerceIn(0, width - 1)
        val right = ceil(bounds.right * scaleX).toInt().coerceIn(0, width - 1)
        val top = floor(height - bounds.top * scaleY).toInt().coerceIn(0, height - 1)
        val bottom = ceil(height - bounds.bottom * scaleY).toInt().coerceIn(0, height - 1)
        return color(left, top, right, bottom, text)
    }

    /** 画素の範囲（両端を含む）で取る。回転したページのように、表示側が座標を画素へ直したときに使う。 */
    fun color(left: Int, top: Int, right: Int, bottom: Int, text: PdfColor): PdfColor {
        val count = mutableMapOf<Int, Int>()
        for (y in top.coerceIn(0, height - 1)..bottom.coerceIn(0, height - 1)) {
            for (x in left.coerceIn(0, width - 1)..right.coerceIn(0, width - 1)) {
                val rgb = pixels[y * width + x] and 0x00ffffff
                val dr = (rgb shr 16) - text.red
                val dg = ((rgb shr 8) and 255) - text.green
                val db = (rgb and 255) - text.blue
                if (dr * dr + dg * dg + db * db < 64 * 64) continue
                count[rgb] = (count[rgb] ?: 0) + 1
            }
        }
        val color = count.maxByOrNull { it.value }?.key ?: return PdfColor.White
        return PdfColor(color shr 16, (color shr 8) and 255, color and 255)
    }
}
