package app.plamotranslate.pipeline.camera

/**
 * 1行の画像の鋭さ（docs/設計/カメラ翻訳.md「追跡と多数決」）。手ぶれ・ピンぼけのコマの読み取りを、多数決で軽くするために測る。
 *
 * 行の高さが [TEXT_HEIGHT] 画素になるように（面積の平均で）縮めて明るさにし、ラプラシアンの分散を明るさの分散で割る。
 * - 行の高さでそろえるので、文字の大きさ（カメラとの距離）が変わっても同じ尺度で比べられる。
 * - 明るさの分散で割るので、露出や照明が変わってコントラストが変わっても、値が引きずられない。
 * 値の絶対の大きさは、書体・文字の詰まり方で行ごとに違い、写る大きさ（[TEXT_HEIGHT] より低い行はそのまま測る。縮めるときの
 * 画素の区切り方）でも少し変わる。比べるのは、同じ行の、同じくらいの大きさに写ったコマどうしだけにする（[LineTracker.Track.ratios]）。
 */
object Sharpness {
    /** 測る前にそろえる、行の高さの画素数（これより低い行は、そのままの大きさで測る）。 */
    const val TEXT_HEIGHT = 24

    /** [argb] は行の範囲の画素（幅 [width]・高さ [height]、ARGB）。測れないほど小さい・一様（白飛びなど）なら null。 */
    fun of(argb: IntArray, width: Int, height: Int): Double? {
        val scale = maxOf(1.0, height.toDouble() / TEXT_HEIGHT)
        val w = (width / scale).toInt()
        val h = (height / scale).toInt()
        if (w < 3 || h < 3) return null
        val g = DoubleArray(w * h)
        for (y in 0 until h) {
            val y0 = (y * scale).toInt()
            val y1 = maxOf(y0 + 1, ((y + 1) * scale).toInt())
            for (x in 0 until w) {
                val x0 = (x * scale).toInt()
                val x1 = maxOf(x0 + 1, ((x + 1) * scale).toInt())
                var sum = 0.0
                for (sy in y0 until y1) for (sx in x0 until x1) sum += luma(argb[sy * width + sx])
                g[y * w + x] = sum / ((y1 - y0) * (x1 - x0))
            }
        }
        val tone = variance(g)
        if (tone < 1e-6) return null
        val lap = DoubleArray((w - 2) * (h - 2))
        var k = 0
        for (y in 1 until h - 1) for (x in 1 until w - 1) {
            val i = y * w + x
            lap[k++] = g[i - 1] + g[i + 1] + g[i - w] + g[i + w] - 4 * g[i]
        }
        return variance(lap) / tone
    }

    private fun luma(c: Int): Double =
        0.299 * (c shr 16 and 0xff) + 0.587 * (c shr 8 and 0xff) + 0.114 * (c and 0xff)

    private fun variance(values: DoubleArray): Double {
        if (values.isEmpty()) return 0.0
        var sum = 0.0
        var sq = 0.0
        for (v in values) {
            sum += v
            sq += v * v
        }
        val n = values.size
        val mean = sum / n
        return (sq / n - mean * mean).coerceAtLeast(0.0)
    }
}
