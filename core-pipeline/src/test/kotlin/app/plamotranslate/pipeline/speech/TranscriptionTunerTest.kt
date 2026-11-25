package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.CpuTopology
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptionTunerTest {
    private fun candidates(n: Int, threads: Int = 0): List<TranscriptionCandidate> {
        val capacities = listOf(3L, 3L, 3L, 3L, 2L, 2L, 1L, 1L).take(n)
        val topology = CpuTopology(capacities.indices.associateWith { capacities[it] })
        return topology.candidates(threads).map { TranscriptionCandidate(false, it, it) }
    }
    private var cpus = candidates(2)
    private val reference = TranscriptionTuner.Reference("Hello!", "en")
    private var environment = TranscriptionTuner.Environment("a", "b", "c", "d", "e", "f", "g")
    private val choices = mutableMapOf<String, TranscriptionChoice>()
    private var onWrite: () -> Unit = {}
    private var onGpuEnd: () -> Unit = {}
    private var onTrial: (TranscriptionCandidate) -> Unit = {}
    private val store = object : TranscriptionTuner.Store {
        override fun read(key: String) = choices[key]
        override fun write(key: String, choice: TranscriptionChoice) { onWrite(); choices[key] = choice }
    }

    private class Engine(val candidate: TranscriptionCandidate) : AutoCloseable {
        var trials = 0
        var closed = false
        override fun close() {
            check(!closed) { "認識器を二重に閉じました" }
            closed = true
        }
    }

    private val engines = mutableListOf<Engine>()
    private val cpuTimes = mutableMapOf<TranscriptionCandidate, Long>()
    private var fallback: String? = null
    private val gpuPreparation = Mutex()
    private var gpuEntries = 0
    private var gpuEnds = 0
    private suspend fun prepare(duration: Long, valid: Boolean = true,
        preference: TranscriptionPreference = TranscriptionPreference.AUTO, allowedGpu: Boolean = true): TranscriptionChoice {
        val (engine, choice) = TranscriptionTuner.prepare(cpus, allowedGpu && fallback == null, preference, environment, reference, store,
            { action -> gpuPreparation.withLock { gpuEntries++; action() } },
            { assertTrue(gpuPreparation.isLocked); true },
            { assertTrue(gpuPreparation.isLocked); fallback = it; gpuEnds++; onGpuEnd() }, { candidate ->
                assertEquals(candidate.gpu, gpuPreparation.isLocked)
                assertTrue(engines.count { !it.closed } <= 1)
                Engine(candidate).also { engines.add(it) }
            }, { engine ->
                assertEquals(engine.candidate.gpu, gpuPreparation.isLocked)
                check(!engine.closed)
                onTrial(engine.candidate)
                engine.trials++
                val gpu = engine.candidate.gpu
                TranscriptionTuner.Sample(Recognition("hello", if (gpu && !valid) "ja" else "en"),
                    if (gpu) duration else cpuTimes.getOrDefault(engine.candidate, 100L))
            })
        engine.use {
            assertEquals(listOf(engine), engines.filter { !it.closed })
            assertEquals(engine.candidate, choice.candidate)
        }
        return choice
    }

    @Test fun `自動では境界を含めてGPUを採用し保存済みなら採用先だけをならす`() = runTest {
        assertTrue(prepare(80).candidate.gpu)
        assertEquals(listOf(4, 4), engines.map { it.trials })
        assertTrue(prepare(90).candidate.gpu)
        assertEquals(1, engines.last().trials)
        environment = environment.copy(projector = "別")
        assertFalse(prepare(81).candidate.gpu)
        assertEquals(listOf(4, 4, 1, 4, 4), engines.map { it.trials })
        assertEquals(3, gpuEntries)
        assertFalse(prepare(1).candidate.gpu)
        assertEquals(3, gpuEntries)
        assertEquals(1, engines.last().trials)
        assertNull(fallback)
    }

    @Test fun `GPU指定では毎回GPUだけを一度ならし自動の選択を上書きしない`() = runTest {
        val automatic = prepare(81)
        val saved = choices.toMap()
        repeat(2) {
            val choice = prepare(123, preference = TranscriptionPreference.GPU)
            assertTrue(choice.candidate.gpu)
            assertEquals(123L, choice.medianNanos)
        }
        assertEquals(listOf(4, 4, 1, 1), engines.map { it.trials })
        assertEquals(saved, choices)
        assertEquals(automatic, prepare(1))
    }

    @Test fun `GPU指定でも正解不一致は退避しCPUの準備をやり直す`() = runTest {
        assertFalse(prepare(1, false, TranscriptionPreference.GPU).candidate.gpu)
        assertFalse(gpuPreparation.isLocked)
        assertNotNull(fallback)
        assertEquals(listOf(1, 4), engines.map { it.trials })
        assertFalse(prepare(1, true, TranscriptionPreference.GPU).candidate.gpu)
        assertEquals(1, gpuEnds)
        assertTrue(choices.isEmpty())
    }

    @Test fun `CPU指定では最速の候補を保存し環境と候補が同じときだけ再利用する`() = runTest {
        val candidates = candidates(8)
        assertEquals(listOf(TranscriptionCandidate(false, 4, 4), TranscriptionCandidate(false, 6, 6),
            TranscriptionCandidate(false, 8, 8)), candidates)
        val fastest = candidates[1]
        val preference = TranscriptionPreference.CPU
        cpus = candidates
        cpuTimes[fastest] = 70L
        val selected = prepare(1, preference = preference)
        assertEquals(fastest, selected.candidate)
        assertEquals(selected, TranscriptionChoice.decode(choices.values.single().encode()))
        assertEquals(selected, prepare(1, preference = preference))
        assertEquals(candidates + fastest, engines.map { it.candidate })
        assertEquals(listOf(4, 4, 1, 1), engines.map { it.trials })

        environment = environment.copy(runtime = "別")
        assertEquals(selected, prepare(1, preference = preference))
        cpus = candidates(2)
        assertEquals(listOf(TranscriptionCandidate(false, 2, 2)), cpus)
        assertEquals(cpus.single(), prepare(1, preference = preference).candidate)
        assertEquals(candidates + cpus, engines.drop(4).map { it.candidate })
        assertEquals(listOf(4, 4, 1, 4), engines.drop(4).map { it.trials })
        assertEquals(0, gpuEntries)
        assertEquals(0, gpuEnds)
    }

    @Test fun `段の数が同じでも許された番号が変われば再計測する`() = runTest {
        for (first in listOf(0, 4, 4)) {
            environment = environment.copy(cpu = CpuTopology(mapOf(first to 10L, first + 1 to 10L)).key)
            prepare(100, preference = TranscriptionPreference.CPU)
        }
        assertEquals(listOf(4, 4, 1), engines.map { it.trials })
    }

    @Test fun `各段を端末の上限に収め同じ候補を一度だけ試す`() {
        val four = TranscriptionCandidate(false, 4, 4)
        assertEquals(listOf(four), candidates(4))
        assertEquals(listOf(four), candidates(4, threads = 8))
        assertEquals(listOf(four, TranscriptionCandidate(false, 6, 6)),
            candidates(6))
    }

    @Test fun `ならしが首位より遅いCPUとGPUを閉じ首位を保存する`() = runTest {
        cpus = candidates(8)
        cpuTimes[cpus[1]] = 50L
        cpuTimes[cpus[2]] = 201L
        val choice = prepare(201)
        assertEquals(listOf(4, 4, 1, 1), engines.map { it.trials })
        assertTrue(engines.all { it.closed })
        assertNull(fallback)
        assertEquals(cpus[1], choice.candidate)
        assertEquals(choice, choices.values.single())
        assertEquals(choice, prepare(1))
        assertEquals(1, engines.last().trials)
    }

    @Test fun `保存した首位が候補に残っていても一覧が変われば測り直す`() = runTest {
        cpus = candidates(8)
        val selected = prepare(1, preference = TranscriptionPreference.CPU)
        cpus = candidates(4)
        assertEquals(selected, prepare(1, preference = TranscriptionPreference.CPU))
        assertEquals(4, engines.last().trials)
        assertEquals(2, choices.size)
    }

    @Test fun `ならしが首位と同じなら3回計測する`() = runTest {
        prepare(100)
        assertEquals(listOf(4, 4), engines.map { it.trials })
    }

    @Test fun `GPU無効の自動選択で保存したCPU候補は自動とCPU指定で再利用できる`() = runTest {
        cpus = candidates(8)
        val fastest = cpus.last()
        cpuTimes[fastest] = 70L
        assertEquals(fastest, prepare(1, allowedGpu = false).candidate)
        assertEquals(fastest, prepare(1, allowedGpu = false).candidate)
        assertEquals(fastest, prepare(1, preference = TranscriptionPreference.CPU).candidate)
        assertEquals(cpus + fastest + fastest, engines.map { it.candidate })
        assertEquals(listOf(4, 4, 4, 1, 1), engines.map { it.trials })
        assertEquals(0, gpuEntries)
        assertEquals(0, gpuEnds)
    }

    @Test fun `自動ではGPUの正解不一致で計測済みCPUを返し退避を選択として保存しない`() = runTest {
        assertFalse(prepare(1, valid = false).candidate.gpu)
        assertFalse(gpuPreparation.isLocked)
        assertNotNull(fallback)
        assertEquals(listOf(4, 1), engines.map { it.trials })
        assertTrue(choices.isEmpty())
    }

    @Test fun `計測中の取消では暫定首位と計測中の認識器を閉じGPU障害を記録しない`() = runTest {
        onTrial = { if (it.gpu) throw CancellationException("取消") }
        assertTrue(runCatching { prepare(80) }.exceptionOrNull() is CancellationException)
        assertFalse(gpuPreparation.isLocked)
        assertEquals(1, gpuEnds)
        assertNull(fallback)
        assertEquals(2, engines.size)
        assertTrue(engines.all { it.closed })
        assertTrue(choices.isEmpty())
    }

    @Test fun `保存やGPU準備記録の更新に失敗したら認識器をすべて閉じる`() = runTest {
        onWrite = { error("保存失敗") }
        assertEquals("保存失敗", runCatching { prepare(80) }.exceptionOrNull()?.message)
        assertNull(fallback)
        assertTrue(engines.all { it.closed })
        onGpuEnd = { error("準備記録の更新失敗") }
        assertEquals("準備記録の更新失敗", runCatching { prepare(80) }.exceptionOrNull()?.message)
        assertNull(fallback)
        assertTrue(engines.all { it.closed })
    }
}
