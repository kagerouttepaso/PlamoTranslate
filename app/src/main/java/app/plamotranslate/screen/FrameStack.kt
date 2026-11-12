package app.plamotranslate.screen

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * 続けて撮った数枚の画面を、画素ごとの中央値で1枚に重ねる（動く画面のモード。2026-11-12、作者の要望
 * 「ゲームで、文字の奥のキャラクターが動いて文字の認識が難しくなる。そういうのに負けない文字認識」。docs/設計/画面翻訳.md）。
 *
 * ゲームの台詞や操作の案内は、数百ミリ秒の間は同じ場所に止まっている。奥で動くキャラクターや光の演出は、
 * 同じ画素に同じ色で居続けない。画素ごとに色の中央値を取ると、止まっている文字は残り、半分未満のコマにしか
 * 写らなかった動きは消える。半透明の台詞の枠の奥で動くものも、枠の色に近い値へならされる。
 * 文字認識は重ねた1枚に1回だけかけるので、コマごとに読んで多数決するより速い（自動の文字認識は1回0.5〜3秒）。
 *
 * 文字そのものが動く（1字ずつ出る台詞、流れる字幕）ときは、中央値に出きっていない字が残る。そうした画面は通常のモードで読む。
 */
internal object FrameStack {

    /** [frames]（同じ大きさ）を画素ごとの中央値で重ねた1枚を返す。1枚ならそのまま返す。渡した画像は呼ぶ側が捨てる。 */
    suspend fun median(frames: List<Bitmap>): Bitmap {
        require(frames.isNotEmpty())
        if (frames.size == 1) return frames[0]
        val width = frames[0].width
        val height = frames[0].height
        return withContext(Dispatchers.Default) {
            val pixels = frames.map { f -> IntArray(width * height).also { f.getPixels(it, 0, width, 0, 0, width, height) } }
            val out = IntArray(width * height)
            // 1枚で約260万画素あるので、行の帯に分けて並列に計算する
            val parts = Runtime.getRuntime().availableProcessors().coerceIn(1, 8)
            val rows = (height + parts - 1) / parts
            coroutineScope {
                (0 until parts).map { p ->
                    async { medianInto(pixels, out, p * rows * width, minOf(height, (p + 1) * rows) * width) }
                }.awaitAll()
            }
            Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
        }
    }

    /**
     * 画素 [from]〜[to] の中央値を色の成分（赤・緑・青）ごとに求めて [out] へ書く。枚数が偶数なら小さい方の中央値。
     * 撮影画像は不透明なので、アルファは不透明にする。
     */
    fun medianInto(frames: List<IntArray>, out: IntArray, from: Int, to: Int) {
        val n = frames.size
        val r = IntArray(n)
        val g = IntArray(n)
        val b = IntArray(n)
        val mid = (n - 1) / 2
        for (i in from until to) {
            for (k in 0 until n) {
                val c = frames[k][i]
                r[k] = (c shr 16) and 0xFF
                g[k] = (c shr 8) and 0xFF
                b[k] = c and 0xFF
            }
            out[i] = (0xFF shl 24) or (select(r, mid) shl 16) or (select(g, mid) shl 8) or select(b, mid)
        }
    }

    /** [values] を小さい順に並べた [k] 番目（枚数は数枚なので挿入ソートで十分速い）。[values] は並べ替わる。 */
    private fun select(values: IntArray, k: Int): Int {
        for (i in 1 until values.size) {
            val v = values[i]
            var j = i - 1
            while (j >= 0 && values[j] > v) {
                values[j + 1] = values[j]
                j--
            }
            values[j + 1] = v
        }
        return values[k]
    }
}
