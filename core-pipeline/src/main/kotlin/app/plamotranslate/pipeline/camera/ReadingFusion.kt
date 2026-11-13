package app.plamotranslate.pipeline.camera

/**
 * 同じ行の何回分もの読み取りを、文字ごとの多数決で1つの文にする（docs/設計/カメラ翻訳.md「追跡と多数決」）。
 * 音声認識の結果を合わせる ROVER と同じ考え方で、1回1回は崩れても、重ねた文は1回の読み取りより正しくなる
 * （2026-11-13、作者の要望「読み続けるほど精度が上がるように」）。
 *
 * 1. いちばん中心的な読み取り（ほかと最も似ているもの）を土台にする。
 * 2. 各読み取りを編集距離で土台に並べ、土台の文字の位置ごとに「どの文字か・抜けか」を、文字の間ごとに「何が挟まったか」を数える。
 * 3. 位置ごとに最も多い文字を採り（同数なら土台の文字、それでも決まらなければ文字の順）、間に挟まった文字は過半数の読み取りが
 *    挟んだときだけ採る。
 *
 * 票には読み取りごとの重み（手ぶれ・ピンぼけのコマは軽い。[LineTracker.Track.weights]）を掛ける。数はすべて重みの合計で比べ、
 * 小数の誤差で同数が崩れないよう、比べる前に [EPSILON] の単位に丸める。
 *
 * 文字はコードポイントの単位で数える（拡張漢字・絵文字のサロゲートペアを分けない）。
 */
object ReadingFusion {
    /** 合わせた文と、その確かさ（土台の文字の位置ごとの、採った文字の得票（重みの合計）の割合の平均。0〜1）。 */
    data class Fused(val text: String, val confidence: Double)

    /** [readings] を合わせる。[backbone] は土台にする読み取りの位置、[weights] は読み取りごとの票の重み（0より大きい）。 */
    fun fuse(readings: List<String>, backbone: Int, weights: List<Double> = List(readings.size) { 1.0 }): Fused {
        val base = readings[backbone].codePoints().toArray()
        val total = weights.sum()
        if (base.isEmpty() || total <= 0.0) return Fused(readings[backbone], 0.0)
        // 位置ごとの票（-1 は抜け）と、間ごとに挟まった文字列の票
        val at = List(base.size) { HashMap<Int, Double>() }
        val between = List(base.size + 1) { HashMap<String, Double>() }
        readings.forEachIndexed { k, r -> align(base, r.codePoints().toArray(), weights[k], at, between) }
        val out = StringBuilder()
        var sum = 0.0
        for (i in 0..base.size) {
            val inserted = between[i].entries.maxWithOrNull(compareBy<Map.Entry<String, Double>> { rounded(it.value) }.thenBy { it.key })
            if (inserted != null && rounded(inserted.value * 2) > rounded(total)) out.append(inserted.key)
            if (i == base.size) break
            val winner = at[i].entries.maxWithOrNull(
                compareBy<Map.Entry<Int, Double>> { rounded(it.value) }.thenBy { if (it.key == base[i]) 1 else 0 }.thenBy { -it.key },
            )!!
            if (winner.key >= 0) out.appendCodePoint(winner.key)
            sum += winner.value / total
        }
        return Fused(out.toString(), sum / base.size)
    }

    /** 票の重みの合計を比べるときの丸めの単位。 */
    private const val EPSILON = 1e-9

    private fun rounded(v: Double): Long = Math.round(v / EPSILON)

    /** [r] を [base] に編集距離で並べ、重み [w] の票を足す。 */
    private fun align(base: IntArray, r: IntArray, w: Double, at: List<HashMap<Int, Double>>, between: List<HashMap<String, Double>>) {
        val m = base.size
        val k = r.size
        val d = Array(m + 1) { IntArray(k + 1) }
        for (i in 0..m) d[i][0] = i
        for (j in 0..k) d[0][j] = j
        for (i in 1..m) for (j in 1..k) {
            val sub = d[i - 1][j - 1] + if (base[i - 1] == r[j - 1]) 0 else 1
            d[i][j] = minOf(sub, d[i - 1][j] + 1, d[i][j - 1] + 1)
        }
        // 後ろからたどる。土台の位置 i に対応する r の文字（無ければ抜け）と、位置の前に挟まった文字列を集める
        val chosen = IntArray(m) { -1 }
        val inserted = Array(m + 1) { StringBuilder() }
        var i = m
        var j = k
        while (i > 0 || j > 0) {
            when {
                i > 0 && j > 0 && d[i][j] == d[i - 1][j - 1] + (if (base[i - 1] == r[j - 1]) 0 else 1) -> {
                    chosen[i - 1] = r[j - 1]
                    i--
                    j--
                }
                i > 0 && d[i][j] == d[i - 1][j] + 1 -> {
                    chosen[i - 1] = -1
                    i--
                }
                else -> {
                    inserted[i].insert(0, String(Character.toChars(r[j - 1])))
                    j--
                }
            }
        }
        for (p in 0 until m) at[p].merge(chosen[p], w, Double::plus)
        for (p in 0..m) if (inserted[p].isNotEmpty()) between[p].merge(inserted[p].toString(), w, Double::plus)
    }
}
