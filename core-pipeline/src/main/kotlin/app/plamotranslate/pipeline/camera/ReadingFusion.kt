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
 * 文字はコードポイントの単位で数える（拡張漢字・絵文字のサロゲートペアを分けない）。
 */
object ReadingFusion {
    /** 合わせた文と、その確かさ（土台の文字の位置ごとの、採った文字の得票の割合の平均。0〜1）。 */
    data class Fused(val text: String, val confidence: Double)

    /** [readings] を合わせる。[backbone] は土台にする読み取りの位置。 */
    fun fuse(readings: List<String>, backbone: Int): Fused {
        val base = readings[backbone].codePoints().toArray()
        val n = readings.size
        if (base.isEmpty()) return Fused(readings[backbone], 0.0)
        // 位置ごとの票（-1 は抜け）と、間ごとに挟まった文字列の票
        val at = List(base.size) { HashMap<Int, Int>() }
        val between = List(base.size + 1) { HashMap<String, Int>() }
        for (r in readings) align(base, r.codePoints().toArray(), at, between)
        val out = StringBuilder()
        var sum = 0.0
        for (i in 0..base.size) {
            val inserted = between[i].entries.maxWithOrNull(compareBy<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            if (inserted != null && inserted.value * 2 > n) out.append(inserted.key)
            if (i == base.size) break
            val winner = at[i].entries.maxWithOrNull(
                compareBy<Map.Entry<Int, Int>> { it.value }.thenBy { if (it.key == base[i]) 1 else 0 }.thenBy { -it.key },
            )!!
            if (winner.key >= 0) out.appendCodePoint(winner.key)
            sum += winner.value.toDouble() / n
        }
        return Fused(out.toString(), sum / base.size)
    }

    /** [r] を [base] に編集距離で並べ、票を足す。 */
    private fun align(base: IntArray, r: IntArray, at: List<HashMap<Int, Int>>, between: List<HashMap<String, Int>>) {
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
        for (p in 0 until m) at[p].merge(chosen[p], 1, Int::plus)
        for (p in 0..m) if (inserted[p].isNotEmpty()) between[p].merge(inserted[p].toString(), 1, Int::plus)
    }
}
