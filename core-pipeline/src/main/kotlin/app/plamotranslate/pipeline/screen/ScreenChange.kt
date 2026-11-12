package app.plamotranslate.pipeline.screen

/** 画面の上の長方形（画素、右と下は含まない）。端末の型（android.graphics.Rect）に依存しないための中核の型。 */
data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int)

/**
 * 画面を縮めた明るさの格子（自動で訳すとき、画面が変わったか・止まったかを見るのに使う）。
 * 横を [ScreenChange.COLS] 個のます目に分け、ます目ごとに数点の明るさ（0〜255）の平均を持つ。
 */
class Thumb(val width: Int, val height: Int, val cols: Int, val rows: Int, val values: IntArray)

/**
 * 画面の変化の計算（自動翻訳。2026-11-12、作者の要望「漫画のページ送りや、Discordでスクロールが止まったら、タップ不要で訳す」）。
 * 撮った画面を [Thumb] に縮め、ます目の明るさを比べる。文字認識より桁違いに軽いので、数百ミリ秒ごとに見張れる。
 * 判定（開始の判定A・有効性の判定B）はこの計算を組み合わせる（docs/設計/画面翻訳.md「判定器」）。
 */
object ScreenChange {
    /** 横のます目の数。1080pxの画面で1つ約22px（本文の1行くらい）。 */
    const val COLS = 48

    /** ます目の明るさがこれ以上違えば、そのます目は変わったとみなす（圧縮の揺れやわずかな色の変化は数える値にしない）。 */
    private const val CELL_DIFF = 16

    /** [width]×[height] の画面を、明るさを返す [luminance] から格子に縮める。ます目1つで縦横3点ずつ見る。 */
    inline fun thumb(width: Int, height: Int, luminance: (x: Int, y: Int) -> Int): Thumb {
        val cols = COLS
        val rows = maxOf(1, Math.round(height.toFloat() / width * cols))
        val values = IntArray(cols * rows)
        for (r in 0 until rows) for (c in 0 until cols) {
            var sum = 0
            for (sy in 0 until 3) for (sx in 0 until 3) {
                val x = ((c + (sx + 0.5f) / 3) * width / cols).toInt().coerceIn(0, width - 1)
                val y = ((r + (sy + 0.5f) / 3) * height / rows).toInt().coerceIn(0, height - 1)
                sum += luminance(x, y)
            }
            values[r * cols + c] = sum / 9
        }
        return Thumb(width, height, cols, rows, values)
    }

    /** ARGB の色の明るさ（おおよそ。0〜255）。 */
    fun luminanceOf(argb: Int): Int = (((argb shr 16) and 0xFF) * 3 + ((argb shr 8) and 0xFF) * 6 + (argb and 0xFF)) / 10

    /** [boxes]（画面の座標）に少しでもかかるます目を true にする。 */
    fun mask(thumb: Thumb, boxes: List<Box>): BooleanArray {
        val mask = BooleanArray(thumb.values.size)
        val cellW = thumb.width.toFloat() / thumb.cols
        val cellH = thumb.height.toFloat() / thumb.rows
        for (r in boxes) {
            val c0 = (r.left / cellW).toInt().coerceIn(0, thumb.cols - 1)
            val c1 = ((r.right - 1) / cellW).toInt().coerceIn(0, thumb.cols - 1)
            val r0 = (r.top / cellH).toInt().coerceIn(0, thumb.rows - 1)
            val r1 = ((r.bottom - 1) / cellH).toInt().coerceIn(0, thumb.rows - 1)
            for (row in r0..r1) for (col in c0..c1) mask[row * thumb.cols + col] = true
        }
        return mask
    }

    /**
     * [boxes] の外側で、[margin] ます以内のます目（外周）を true にする。箱そのもののます目は含まない。
     * 訳の枠の外周は、原文のすぐ周り（吹き出しの縁・メッセージの周り・台詞の枠）で、原文と一緒に動き・消える（有効性の判定B）。
     */
    fun ring(thumb: Thumb, boxes: List<Box>, margin: Int): BooleanArray {
        val inside = mask(thumb, boxes)
        val ring = BooleanArray(inside.size)
        for (row in 0 until thumb.rows) for (col in 0 until thumb.cols) {
            if (!inside[row * thumb.cols + col]) continue
            for (dr in -margin..margin) for (dc in -margin..margin) {
                val r = row + dr
                val c = col + dc
                if (r < 0 || c < 0 || r >= thumb.rows || c >= thumb.cols) continue
                val i = r * thumb.cols + c
                if (!inside[i]) ring[i] = true
            }
        }
        return ring
    }

    /**
     * 続けて縮めた数枚を、ます目ごとの明るさの中央値で1枚にする（動く画面のモード。FrameStack と同じ考え方）。
     * 背景で動くキャラクターや演出は消え、止まっている台詞の枠やUIの変化だけが残る。大きさの違う格子が混ざれば最後の1枚を返す。
     */
    fun median(thumbs: List<Thumb>): Thumb {
        val last = thumbs.last()
        if (thumbs.size < 3 || thumbs.any { it.cols != last.cols || it.rows != last.rows }) return last
        val values = IntArray(last.values.size)
        val buffer = IntArray(thumbs.size)
        for (i in values.indices) {
            for (k in thumbs.indices) buffer[k] = thumbs[k].values[i]
            buffer.sort()
            values[i] = buffer[(buffer.size - 1) / 2]
        }
        return Thumb(last.width, last.height, last.cols, last.rows, values)
    }

    /**
     * 続けて縮めた数枚の間で明るさが動いたます目（動く背景）を true にする。動く画面のモードの開始の判定Aでは、ここを比べる対象から外し、
     * 止まっているます目（UI・台詞の枠）だけで、画面が止まったかを見る。
     */
    fun active(thumbs: List<Thumb>): BooleanArray? {
        val last = thumbs.lastOrNull() ?: return null
        if (thumbs.any { it.cols != last.cols || it.rows != last.rows }) return null
        return BooleanArray(last.values.size) { i ->
            var lo = 255
            var hi = 0
            for (t in thumbs) {
                val v = t.values[i]
                if (v < lo) lo = v
                if (v > hi) hi = v
            }
            hi - lo >= CELL_DIFF
        }
    }

    /** 覆いを裏返す（[mask] の中だけを比べたいとき、[mask] の結果を裏返して渡す）。 */
    fun invert(mask: BooleanArray): BooleanArray = BooleanArray(mask.size) { !mask[it] }

    /** 2つの覆いを合わせる（どちらかで true なら true）。大きさが違えば [a] を返す。 */
    fun union(a: BooleanArray, b: BooleanArray?): BooleanArray =
        if (b == null || b.size != a.size) a else BooleanArray(a.size) { a[it] || b[it] }

    /**
     * [a] と [b] で変わったます目の割合（[mask] が true のます目は数えない）。画面の大きさが違えば（回した）1。
     */
    fun changedFraction(a: Thumb, b: Thumb, mask: BooleanArray? = null): Float {
        if (a.cols != b.cols || a.rows != b.rows || a.width != b.width || a.height != b.height) return 1f
        var counted = 0
        var changed = 0
        for (i in a.values.indices) {
            if (mask != null && mask[i]) continue
            counted++
            if (kotlin.math.abs(a.values[i] - b.values[i]) >= CELL_DIFF) changed++
        }
        return if (counted == 0) 0f else changed.toFloat() / counted
    }

    /** 数えるます目（[mask] が false）の数。 */
    fun counted(mask: BooleanArray): Int = mask.count { !it }
}
