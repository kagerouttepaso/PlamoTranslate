package app.plamotranslate.overlay

import android.graphics.Bitmap
import android.graphics.Rect
import kotlin.math.sqrt

/**
 * 訳の枠を、文字を囲む吹き出しの内側いっぱいに広げる（2026-11-12、作者の採用。浮遊ボタンの長押しで切り替える）。
 * 漫画の小さな文字は、元の文字の範囲に訳を収めると7sp前後まで縮んで読みにくかった。吹き出しまで広げると大きな字で描け、
 * 元の文字もしっかり覆える。
 *
 * 文字の周りの背景色の範囲を、格子の上で塗りつぶして求める。Discordやゲームの画面のように背景色が一面に続くと、
 * 範囲が探す窓の端まで届くので「開いている」とみなし、広げない（枠が投稿欄全体に広がって名前やほかの投稿を覆わないように）。
 * 吹き出しの線などで閉じていて、元の枠の [MAX_GROWTH] 倍以内に収まるときだけ広げる。
 */
internal object BubbleFit {
    /** 背景色とみなす色の距離（RGBの差の長さ）。吹き出しの白と、線のにじみを分ける。 */
    private const val BACKGROUND_DISTANCE = 48.0

    /** 広げた範囲の面積が元の枠のこれ倍を超えたら、吹き出しではないとみなす。 */
    private const val MAX_GROWTH = 6

    /** 広げた範囲がこれ倍未満なら、広げる意味が小さいので広げない。 */
    private const val MIN_GROWTH = 1.15

    /**
     * [rect]（文字の範囲）の周りの [background] 色の範囲が閉じていれば、その内側の範囲を返す。開いていれば null。
     * [search] は探す窓の広さ（元の枠の長い辺に対する割合）。
     */
    fun expand(bitmap: Bitmap, rect: Rect, background: Int, search: Float = 0.8f): Rect? {
        val reach = (maxOf(rect.width(), rect.height()) * search).toInt().coerceAtLeast(8)
        val window = Rect(rect).apply { inset(-reach, -reach) }
        // 窓が画面の外へはみ出す向きは、画面の端を壁とせず「開いている」とみなすため、はみ出したまま扱う
        val step = (minOf(rect.width(), rect.height()) / 10).coerceIn(2, 6)
        val cols = window.width() / step
        val rows = window.height() / step
        if (cols < 3 || rows < 3) return null
        val px = IntArray(cols * rows) { -1 }
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = window.left + c * step + step / 2
            val y = window.top + r * step + step / 2
            if (x in 0 until bitmap.width && y in 0 until bitmap.height) px[r * cols + c] = bitmap.getPixel(x, y)
        }
        val inside = fill(cols, rows, px, background, rect, window, step) ?: return null
        val (c0, r0, c1, r1) = inscribed(inside, cols, rows, rect, window, step)
        // 吹き出しの線にかからないよう、1ます内側にする
        val out = Rect(
            window.left + (c0 + 1) * step, window.top + (r0 + 1) * step,
            window.left + c1 * step, window.top + r1 * step,
        )
        out.union(rect)
        val grown = out.width().toDouble() * out.height() / (rect.width().toDouble() * rect.height()).coerceAtLeast(1.0)
        return out.takeIf { grown >= MIN_GROWTH }
    }

    /**
     * 文字の範囲のます目から始め、背景色のます目へ広げる。窓の端（画面の外を含む）に届いたら開いているとして null。
     * 届いたます目を true にして返す。
     */
    fun fill(cols: Int, rows: Int, px: IntArray, background: Int, rect: Rect, window: Rect, step: Int): BooleanArray? {
        val seen = BooleanArray(cols * rows)
        val queue = ArrayDeque<Int>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val x = window.left + c * step + step / 2
            val y = window.top + r * step + step / 2
            if (x >= rect.left && x < rect.right && y >= rect.top && y < rect.bottom) {
                seen[r * cols + c] = true
                queue.addLast(r * cols + c)
            }
        }
        if (queue.isEmpty()) return null
        val limit = queue.size * MAX_GROWTH
        var count = 0
        while (queue.isNotEmpty()) {
            val i = queue.removeFirst()
            val c = i % cols
            val r = i / cols
            if (c == 0 || r == 0 || c == cols - 1 || r == rows - 1 || px[i] == -1) return null
            if (++count > limit) return null
            for ((dc, dr) in NEIGHBORS) {
                val j = (r + dr) * cols + (c + dc)
                if (!seen[j] && near(px[j], background)) {
                    seen[j] = true
                    queue.addLast(j)
                }
            }
        }
        return seen
    }

    /**
     * 届いた範囲（[inside]）に収まる長方形の（左の列, 上の行, 右の列, 下の行）。届いた範囲を囲む長方形から始め、
     * 辺のます目に範囲の外があれば、その辺を内側へ1ますずつ縮める。楕円の吹き出しで、外接する長方形まで広げると
     * 四隅が線の外へはみ出し、訳の字が線にかかった（2026-11-12）。文字の範囲（[rect]）より内側には縮めない。
     */
    private fun inscribed(inside: BooleanArray, cols: Int, rows: Int, rect: Rect, window: Rect, step: Int): IntArray {
        var c0 = cols; var r0 = rows; var c1 = 0; var r1 = 0
        for (i in inside.indices) if (inside[i]) {
            c0 = minOf(c0, i % cols); r0 = minOf(r0, i / cols); c1 = maxOf(c1, i % cols); r1 = maxOf(r1, i / cols)
        }
        // 文字の範囲が入るます目（これより内側には縮めない）
        val tc0 = ((rect.left - window.left) / step).coerceIn(0, cols - 1)
        val tr0 = ((rect.top - window.top) / step).coerceIn(0, rows - 1)
        val tc1 = ((rect.right - 1 - window.left) / step).coerceIn(0, cols - 1)
        val tr1 = ((rect.bottom - 1 - window.top) / step).coerceIn(0, rows - 1)
        fun rowOk(r: Int) = (c0..c1).all { inside[r * cols + it] }
        fun colOk(c: Int) = (r0..r1).all { inside[it * cols + c] }
        var changed = true
        while (changed) {
            changed = false
            if (!rowOk(r0) && r0 < tr0) { r0++; changed = true }
            if (!rowOk(r1) && r1 > tr1) { r1--; changed = true }
            if (!colOk(c0) && c0 < tc0) { c0++; changed = true }
            if (!colOk(c1) && c1 > tc1) { c1--; changed = true }
        }
        return intArrayOf(c0, r0, c1, r1)
    }

    private val NEIGHBORS = listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)

    private fun near(c: Int, background: Int): Boolean {
        if (c == -1) return true // 画面の外：端に届いたことにする（fill で null になる）
        val dr = ((c shr 16) and 0xFF) - ((background shr 16) and 0xFF)
        val dg = ((c shr 8) and 0xFF) - ((background shr 8) and 0xFF)
        val db = (c and 0xFF) - (background and 0xFF)
        return sqrt((dr * dr + dg * dg + db * db).toDouble()) < BACKGROUND_DISTANCE
    }
}
