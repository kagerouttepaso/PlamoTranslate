package app.plamotranslate.pipeline.compute

/** 同じ入力でならしと本計測を分け、最速候補のならしを次の打ち切り基準にする。 */
class ThreadTrials {
    private var bestWarmup = Long.MAX_VALUE
    private var bestMedian = Long.MAX_VALUE

    suspend fun measure(sample: suspend () -> Long, log: (Long, Long?) -> Unit = { _, _ -> }): Long? {
        val warmup = sample()
        if (warmup > bestWarmup) {
            log(warmup, null)
            return null
        }
        val median = List(3) { sample() }.sorted()[1]
        log(warmup, median)
        if (median < bestMedian) {
            bestWarmup = warmup
            bestMedian = median
        }
        return median
    }
}
