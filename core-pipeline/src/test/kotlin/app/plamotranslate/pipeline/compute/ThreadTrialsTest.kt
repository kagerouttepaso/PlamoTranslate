package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Files

class ThreadTrialsTest {
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
