package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ThreadTrialsTest {
    private suspend fun search(boundaries: List<Int>, times: List<Long>, spread: Long = 0): Pair<List<Int>, Int> {
        val visited = mutableListOf<Int>()
        val curve = ThreadTrials()
        curve.search(boundaries, 1..times.size) { n ->
            visited += n
            val median = times[n - 1]
            val samples = listOf(median, median - spread, median, median + spread).iterator()
            curve.measure(n, { samples.next() }) {}
        }
        return visited to curve.preferred()
    }

    @Test fun 境目の間の最速を探し改善が止まれば多い側を閉じる() = runTest {
        assertEquals(listOf(2, 8, 1, 3, 4, 5, 6) to 5,
            search(listOf(2, 8), listOf(150, 120, 100, 90, 70, 82, 140, 200)))
    }

    @Test fun 少ない側はばらつきの範囲を超えた所で止める() = runTest {
        assertEquals(listOf(1, 4, 8, 3, 5, 6, 7) to 4,
            search(listOf(1, 4, 8), listOf(300, 240, 180, 104, 102, 100, 140, 800), 5))
    }

    @Test fun 予算が一つなら一度だけ測る() = runTest {
        assertEquals(listOf(1) to 1, search(listOf(1), listOf(90)))
    }

    @Test fun 最速との差が反復の偏差に収まる最小の数を選ぶ() = runTest {
        val curve = ThreadTrials()
        for ((n, samples) in listOf(
            1 to listOf(10L, 150L, 150L, 151L),
            2 to listOf(10L, 99L, 102L, 106L),
            3 to listOf(10L, 99L, 100L, 101L),
        )) {
            val iterator = samples.iterator()
            curve.measure(n, { iterator.next() }) {}
        }
        assertEquals(2, curve.preferred())
        assertEquals(2, ThreadTrials.decode(curve.encode()).preferred())
    }

    @Test fun ならしの初回費用が大きくても本計測が速い候補を測って選ぶ() = runTest {
        val curve = ThreadTrials()
        // 15T Pro の Qwen3-ASR：4本はならしに初回の用意が入って1本より遅いが、本計測では速い。
        for ((n, samples) in listOf(1 to listOf(974L, 929L, 936L, 964L), 4 to listOf(1060L, 730L, 725L, 740L))) {
            val iterator = samples.iterator()
            curve.measure(n, { iterator.next() }) {}
        }
        assertEquals(4, curve.preferred())
    }

    @Test fun 本計測の2回が最速の最も遅い回より遅ければ残りを測らない() = runTest {
        val curve = ThreadTrials()
        var runs = 0
        curve.measure(4, { runs++; 100L }) {}
        assertEquals(null, curve.measure(6, { runs++; 3600L }) {})
        assertEquals(7, runs)
        assertEquals(4, curve.preferred())
    }

    @Test fun 本計測の1回だけの遅れでは閉じずに中央値で比べる() = runTest {
        val curve = ThreadTrials()
        // 15T Pro の Qwen3-ASR：4本の本計測の1回目がメモリの回収と重なって遅れた。
        for ((n, samples) in listOf(1 to listOf(980L, 930L, 945L, 952L), 4 to listOf(727L, 1763L, 730L, 740L))) {
            val iterator = samples.iterator()
            curve.measure(n, { iterator.next() }) {}
        }
        assertEquals(4, curve.preferred())
    }

    @Test fun 計測中と中央値と打ち切りと選択を通知して終了時に解除する() = runTest {
        val observed = mutableListOf<ThreadTrialProgress?>()
        val collector = launch(kotlinx.coroutines.test.UnconfinedTestDispatcher(testScheduler)) {
            ActiveThreadTrials.progress.collect { observed += it }
        }
        try {
            ThreadBenchmark("部品", "1", null) { n -> n * 100L }
                .select("CPU", listOf(1, 2), { true }) { curve, n -> curve.measure(n, { n * 100L }) {} }
            val measured = ThreadTrialRow.Measured(100)
            assertEquals(listOf(null,
                listOf(ThreadTrialRow.Measuring, ThreadTrialRow.Pending),
                listOf(measured, ThreadTrialRow.Pending),
                listOf(measured, ThreadTrialRow.Measuring),
                listOf(measured, ThreadTrialRow.CutOff),
                listOf(measured, ThreadTrialRow.CutOff), null), observed.map { it?.rows })
            assertEquals(1, observed[observed.lastIndex - 1]?.selected)
        } finally {
            collector.cancel()
        }
    }

    @Test fun 同じ部品と版とCPUの組でだけ保存した関係を使う() = runTest {
        val dir = Files.createTempDirectory("threads").toFile()
        var measurements = 0
        try {
            suspend fun selected(cpu: String, version: String): Int {
                val benchmark = ThreadBenchmark("部品", version, dir) { n -> 100L / n }
                return benchmark.select(cpu, listOf(1, 2), { true }) { curve, n ->
                    measurements++
                    curve.measure(n, { benchmark.sample(n) }) {}
                }.preferred()
            }
            assertEquals(2, selected("A", "1"))
            assertEquals(2, selected("A", "1"))
            assertEquals(2, measurements)
            val saved = dir.listFiles()!!.single().readText()
            assertEquals(2, ThreadTrials.decode(saved).preferred())
            assertEquals(2, selected("B", "1"))
            assertEquals(2, selected("A", "2"))
            assertEquals(6, measurements)
        } finally {
            dir.deleteRecursively()
        }
    }
}
