package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class TranscriptionTunerTest {
    private val cpus = TranscriptionTuner.cpuCandidates(2)
    private val reference = TranscriptionTuner.Reference("Hello!", "en")
    private var environment = TranscriptionTuner.Environment("a", "b", "c", "d", "e", "f", "g")
    private val choices = mutableMapOf<String, TranscriptionChoice>()
    private var onWrite: () -> Unit = {}
    private var onGpuEnd: () -> Unit = {}
    private val store = object : TranscriptionTuner.Store {
        override fun read(key: String) = choices[key]
        override fun write(key: String, choice: TranscriptionChoice) { onWrite(); choices[key] = choice }
    }
    private var open = 0
    private var gpuLoads = 0
    private var fallback: String? = null
    private var clock = 0L
    private suspend fun prepare(duration: Long, valid: Boolean = true, preference: TranscriptionPreference = TranscriptionPreference.AUTO): Boolean {
        class Engine(val gpu: Boolean) : AutoCloseable { override fun close() { open-- } }
        val (engine, choice) = TranscriptionTuner.prepare(cpus, fallback == null, preference, environment, reference, store,
            { true }, { fallback = it; onGpuEnd() }, { candidate ->
                check(open++ == 0)
                if (candidate.gpu) gpuLoads++
                Engine(candidate.gpu)
            }, { engine ->
                val start = clock
                clock += if (engine.gpu) duration else 100
                TranscriptionTuner.Sample(Recognition("hello", if (!engine.gpu || valid) "en" else "ja"), clock - start)
            })
        engine.close()
        return choice.candidate.gpu
    }

    @Test fun `境界を含めて採用し一致時は採用先だけをならす`() = runTest {
        assertTrue(prepare(80)); val before = clock
        assertTrue(prepare(90)); assertEquals(90L, clock - before)
        environment = environment.copy(projector = "別")
        assertFalse(prepare(81)); assertNull(fallback)
    }

    @Test fun `強制GPUでも正解不一致は退避しCPUの準備をやり直す`() = runTest {
        assertFalse(prepare(1, false, TranscriptionPreference.GPU)); assertNotNull(fallback)
        val before = gpuLoads
        assertFalse(prepare(1, true, TranscriptionPreference.GPU)); assertEquals(before, gpuLoads)
        assertTrue(choices.isEmpty())
    }

    @Test fun `CPU指定はGPUに触れずスレッド数はCPU数を越えない`() = runTest {
        assertFalse(prepare(1, preference = TranscriptionPreference.CPU)); assertEquals(0, gpuLoads)
        assertEquals(listOf(TranscriptionCandidate(false, 2, 2)), cpus)
    }

    @Test fun `環境で使えるスレッド数の組だけを試し最速の組を保存する`() = runTest {
        val candidates = TranscriptionTuner.cpuCandidates(8)
        val split = TranscriptionCandidate(false, 8, 4)
        assertEquals(listOf(TranscriptionCandidate(false, 4, 4), TranscriptionCandidate(false, 6, 6), split), candidates)
        val tried = mutableListOf<TranscriptionCandidate>()
        class Engine(val candidate: TranscriptionCandidate) : AutoCloseable { override fun close() {} }
        suspend fun prepare() = TranscriptionTuner.prepare(candidates, false, TranscriptionPreference.AUTO,
            environment, reference, store, { error("GPU は無効") }, {}, { Engine(it).also { tried.add(it.candidate) } },
            { TranscriptionTuner.Sample(Recognition("Hello", "en"), if (it.candidate == split) 70 else 100) })
        val (engine, choice) = prepare()
        engine.close()
        assertEquals(split, choice.candidate)
        assertEquals(choice, TranscriptionChoice.decode(choice.encode()))
        tried.clear()
        prepare().first.close()
        assertEquals(listOf(split), tried)
    }

    @Test fun `保存失敗はGPU障害にせず準備済みの認識器を閉じる`() = runTest {
        onWrite = { error("保存失敗") }
        assertEquals("保存失敗", runCatching { prepare(80) }.exceptionOrNull()?.message)
        assertNull(fallback); assertEquals(0, open)
        onGpuEnd = { error("準備記録の更新失敗") }
        assertEquals("準備記録の更新失敗", runCatching { prepare(80) }.exceptionOrNull()?.message)
        assertNull(fallback); assertEquals(0, open)
    }
}
