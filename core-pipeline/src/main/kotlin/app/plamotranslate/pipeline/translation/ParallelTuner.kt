package app.plamotranslate.pipeline.translation

/**
 * 判定：同時に訳す数の自動調整（docs/設計/翻訳コア.md「自動で調整」。2026-11-12、作者の要望「順に全部試すのではなく、
 * 少ない手数で最適な設定に入る形に」）。候補は1・2・4・8本。全部は測らず、3回で決める。測るのは呼ぶ側（設定の画面）で、
 * ここは測った速さから次に測る本数と選ぶ本数を決めるだけにする。
 *
 * 1. まず4本を測る（REDMAGICで測った最適値に近い真ん中）。これを最初の設定として入れる。
 * 2. 1本を測る（比べる基準）。
 * 3. 4本が1本より [GAIN] 以上速ければ上の8本を、そうでなければ下の2本を測る。
 *
 * 決め方：一番速い本数を選ぶ。ただし、少ない本数でも一番速い値の [NEAR] 以上出ていれば、少ない方を選ぶ
 * （同時に訳す数が多いほど、1つのチャンクが短くなって前後の文が訳の手がかりにならず、メモリも使う）。
 */
object ParallelTuner {
    private const val GAIN = 1.1
    private const val NEAR = 0.9

    /** 次に測る本数。決まったら null。[measured] は本数ごとの速さ（測れなかった本数は0）。 */
    fun next(measured: Map<Int, Double>): Int? = when {
        4 !in measured -> 4
        1 !in measured -> 1
        measured.size >= 3 -> null
        measured.getValue(4) >= measured.getValue(1) * GAIN -> 8
        else -> 2
    }

    /** これまでに測った中で選ぶ本数。まだ何も測っていなければ [fallback]（エンジンの既定）。 */
    fun best(measured: Map<Int, Double>, fallback: Int): Int {
        val top = measured.values.maxOrNull() ?: return fallback
        if (top <= 0.0) return 1
        return measured.filterValues { it >= top * NEAR }.keys.min()
    }
}
