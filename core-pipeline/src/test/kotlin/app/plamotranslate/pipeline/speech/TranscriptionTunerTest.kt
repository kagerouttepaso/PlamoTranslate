package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptionTunerTest {
    private var cpus = TranscriptionTuner.cpuCandidates(2)
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
        val candidates = TranscriptionTuner.cpuCandidates(8)
        assertEquals(listOf(TranscriptionCandidate(false, 4, 4), TranscriptionCandidate(false, 6, 6),
            TranscriptionCandidate(false, 8, 4)), candidates)
        val fastest = candidates[1]
        val preference = TranscriptionPreference.CPU
        cpus = candidates
        cpuTimes[fastest] = 70L
        val selected = prepare(1, preference = preference)
        assertEquals(fastest, selected.candidate)
        assertEquals(selected, TranscriptionChoice.decode(choices.values.single().encode()))
        assertEquals(selected, prepare(1, preference = preference))
        assertEquals(candidates + fastest, engines.map { it.candidate })
        assertEquals(listOf(4, 4, 4, 1), engines.map { it.trials })

        environment = environment.copy(runtime = "別")
        assertEquals(selected, prepare(1, preference = preference))
        cpus = TranscriptionTuner.cpuCandidates(2)
        assertEquals(listOf(TranscriptionCandidate(false, 2, 2)), cpus)
        assertEquals(cpus.single(), prepare(1, preference = preference).candidate)
        assertEquals(candidates + cpus, engines.drop(4).map { it.candidate })
        assertTrue(engines.drop(4).all { it.trials == 4 })
        assertEquals(0, gpuEntries)
        assertEquals(0, gpuEnds)
    }

    @Test fun `GPU無効の自動選択で保存したCPU候補は自動とCPU指定で再利用できる`() = runTest {
        cpus = TranscriptionTuner.cpuCandidates(8)
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
        assertEquals(listOf(4, 4), engines.map { it.trials })
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
