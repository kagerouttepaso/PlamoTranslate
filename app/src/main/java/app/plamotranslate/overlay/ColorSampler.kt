package app.plamotranslate.overlay

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** 訳の枠に使う背景色と文字色。 */
data class BoxColors(val background: Int, val text: Int) {
    companion object {
        /** 読み取れなかったときの既定（今までの暗い枠と白い文字）。 */
        val DEFAULT = BoxColors(Color.rgb(28, 28, 34), Color.WHITE)
    }
}

/**
 * 撮影した画面から、訳の枠の背景色と文字色を読み取る（2026-11-05、作者の要望「吹き出しの置き換えの背景と文字色を元の画面とそろえたい」）。
 *
 * - 背景色：枠を少し広げた外周の画素を、各色32段階にまとめて最も多い色にする。文字が外周にかかっても多数決で背景が残る。
 * - 文字色：枠の中で背景色から十分に離れた画素の中央値にする。
 * - 文字色は、読みやすさのために灰色なら黒か白に、色付きならコントラスト比7まで濃くする（[readable]）。
 */
object ColorSampler {
    private const val RING = 3
    private const val TEXT_DISTANCE = 90.0

    /** 訳の文字に求めるコントラスト比（WCAG 2.xのAAA、本文7:1）。以前は3未満だけ直していた。 */
    private const val MIN_CONTRAST = 7.0

    /** これ未満の彩度の文字色は、灰色とみなして黒か白にする。 */
    private const val GRAY_SATURATION = 0.25

    fun sample(bitmap: Bitmap, rect: Rect): BoxColors {
        val outer = Rect(rect).apply { inset(-RING * 2, -RING * 2) }
        if (!outer.intersect(0, 0, bitmap.width, bitmap.height) || outer.width() < 4 || outer.height() < 4) {
            return BoxColors.DEFAULT
        }
        val pixels = IntArray(outer.width() * outer.height())
        bitmap.getPixels(pixels, 0, outer.width(), outer.left, outer.top, outer.width(), outer.height())
        fun at(x: Int, y: Int) = pixels[(y - outer.top) * outer.width() + (x - outer.left)]

        // 背景：外周の帯（幅RING）の画素の最頻色
        val counts = HashMap<Int, Int>()
        val sums = HashMap<Int, LongArray>()
        for (y in outer.top until outer.bottom) {
            for (x in outer.left until outer.right) {
                val onRing = x < outer.left + RING || x >= outer.right - RING || y < outer.top + RING || y >= outer.bottom - RING
                if (!onRing) continue
                val c = at(x, y)
                val key = (Color.red(c) shr 3 shl 10) or (Color.green(c) shr 3 shl 5) or (Color.blue(c) shr 3)
                counts[key] = (counts[key] ?: 0) + 1
                sums.getOrPut(key) { LongArray(3) }.let {
                    it[0] += Color.red(c).toLong(); it[1] += Color.green(c).toLong(); it[2] += Color.blue(c).toLong()
                }
            }
        }
        val bgKey = counts.maxByOrNull { it.value }?.key ?: return BoxColors.DEFAULT
        val n = counts.getValue(bgKey)
        val s = sums.getValue(bgKey)
        val background = Color.rgb((s[0] / n).toInt(), (s[1] / n).toInt(), (s[2] / n).toInt())

        // 文字：枠の中で背景から十分に離れた画素の中央値（各色ごと）
        val inner = Rect(rect).apply { intersect(outer) }
        val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
        for (y in inner.top until inner.bottom) {
            for (x in inner.left until inner.right) {
                val c = at(x, y)
                if (distance(c, background) >= TEXT_DISTANCE) {
                    rs += Color.red(c); gs += Color.green(c); bs += Color.blue(c)
                }
            }
        }
        var text = if (rs.size >= 8) {
            Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
        } else {
            fallbackText(background)
        }
        return BoxColors(background, readable(text, background))
    }

    /**
     * 訳の文字色を読みやすくする（2026-11-12、作者の指摘「漫画の白背景に黒い小さな文字で、訳が読みにくい。特に文字色が灰色っぽいとき」）。
     * 小さい文字は縁のぼけた画素が多く、中央値が灰色になる。灰色の訳文は、枠から透ける元の文字と同じくらいの濃さになって読みにくかった。
     * - 灰色っぽい文字（彩度が [GRAY_SATURATION] 未満）は、背景に合わせて黒か白にする。
     * - 色の付いた文字は色味を残し、コントラスト比が [MIN_CONTRAST]（WCAGのAAA）に届くまで黒か白へ寄せる。
     */
    fun readable(text: Int, background: Int): Int {
        if (saturation(text) < GRAY_SATURATION) return fallbackText(background)
        if (contrast(text, background) >= MIN_CONTRAST) return text
        val target = fallbackText(background)
        for (step in 1..10) {
            val c = blend(text, target, step / 10.0)
            if (contrast(c, background) >= MIN_CONTRAST) return c
        }
        return target
    }

    /** 背景に対して黒と白のどちらがはっきり見えるか（コントラスト比が等しくなる相対輝度0.179で分ける）。 */
    private fun fallbackText(background: Int) = if (luminance(background) > 0.179) BLACK else WHITE

    private fun r(c: Int) = (c shr 16) and 0xFF
    private fun g(c: Int) = (c shr 8) and 0xFF
    private fun b(c: Int) = c and 0xFF
    private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    private fun blend(x: Int, y: Int, t: Double): Int = rgb(
        (r(x) + (r(y) - r(x)) * t).toInt(),
        (g(x) + (g(y) - g(x)) * t).toInt(),
        (b(x) + (b(y) - b(x)) * t).toInt(),
    )

    /** HSVの彩度（0〜1）。 */
    private fun saturation(c: Int): Double {
        val hi = maxOf(r(c), g(c), b(c))
        val lo = minOf(r(c), g(c), b(c))
        return if (hi == 0) 0.0 else (hi - lo).toDouble() / hi
    }

    private fun distance(x: Int, y: Int): Double {
        val dr = (r(x) - r(y)).toDouble()
        val dg = (g(x) - g(y)).toDouble()
        val db = (b(x) - b(y)).toDouble()
        return sqrt(dr * dr + dg * dg + db * db)
    }

    /** WCAGの相対輝度。 */
    private fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch(r(c)) + 0.7152 * ch(g(c)) + 0.0722 * ch(b(c))
    }

    /** WCAGのコントラスト比（1〜21）。 */
    fun contrast(x: Int, y: Int): Double {
        val la = luminance(x); val lb = luminance(y)
        return (max(la, lb) + 0.05) / (min(la, lb) + 0.05)
    }

    private val BLACK = rgb(0, 0, 0)
    private val WHITE = rgb(255, 255, 255)
}
