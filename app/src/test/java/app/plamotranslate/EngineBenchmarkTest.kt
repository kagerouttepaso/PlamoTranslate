package app.plamotranslate

import org.junit.Assert.assertEquals
import org.junit.Test

/** 速さの計測の計算（設定の「速さを測る」）。 */
class EngineBenchmarkTest {
    @Test
    fun speedExcludesTimeToFirstToken() {
        // 最初の文字まで 500ms、その後の 10 トークンに 1000ms → 10 トークン/秒
        assertEquals(10.0, EngineBenchmark.Result(firstMillis = 500, tokens = 11, totalMillis = 1500).tokensPerSecond, 1e-9)
    }

    @Test
    fun speedIsZeroWhenNothingWasGeneratedAfterFirstToken() {
        assertEquals(0.0, EngineBenchmark.Result(firstMillis = 500, tokens = 1, totalMillis = 500).tokensPerSecond, 0.0)
    }

    /** 同時に訳す数の自動調整：決まるまで測り、選んだ本数と測った順を返す。 */
    private fun tune(rates: Map<Int, Double>): Pair<Int, List<Int>> {
        val measured = LinkedHashMap<Int, Double>()
        while (true) {
            val n = ParallelTuner.next(measured) ?: break
            measured[n] = rates.getValue(n)
        }
        return ParallelTuner.best(measured) to measured.keys.toList()
    }

    @Test
    fun tunerPicksEightWhenItKeepsGettingFaster() {
        // REDMAGICのGPU・0.8B（2026-11-12）：2本では伸びないが、4本・8本で伸びる
        assertEquals(8 to listOf(4, 1, 8), tune(mapOf(1 to 58.0, 2 to 53.0, 4 to 99.0, 8 to 115.0)))
    }

    @Test
    fun tunerKeepsFourWhenEightIsNoFaster() {
        // REDMAGICのGPU・2B：8本にしても4本と同じ
        assertEquals(4 to listOf(4, 1, 8), tune(mapOf(1 to 26.0, 2 to 24.0, 4 to 44.0, 8 to 44.0)))
    }

    @Test
    fun tunerPicksOneWhenParallelDoesNotHelp() {
        assertEquals(1 to listOf(4, 1, 2), tune(mapOf(1 to 50.0, 2 to 45.0, 4 to 48.0, 8 to 50.0)))
    }

    @Test
    fun tunerPicksTwoWhenOnlyTwoHelps() {
        assertEquals(2 to listOf(4, 1, 2), tune(mapOf(1 to 50.0, 2 to 60.0, 4 to 52.0, 8 to 40.0)))
    }

    @Test
    fun tunerFallsBackToOneWhenNothingCouldBeMeasured() {
        assertEquals(1, ParallelTuner.best(mapOf(4 to 0.0, 1 to 0.0, 2 to 0.0)))
    }
}
