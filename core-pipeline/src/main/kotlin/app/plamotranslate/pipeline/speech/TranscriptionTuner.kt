package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class TranscriptionPreference { AUTO, CPU, GPU }
data class TranscriptionCandidate(val gpu: Boolean, val batch: Int, val generation: Int)
data class TranscriptionChoice(val candidate: TranscriptionCandidate, val medianNanos: Long, val reason: String) {
    fun encode() = listOf(candidate.gpu, candidate.batch, candidate.generation, medianNanos, reason).joinToString("|")

    companion object {
        fun decode(value: String?): TranscriptionChoice? {
            val fields = value?.split('|', limit = 5) ?: return null
            if (fields.size != 5) return null
            val candidate = TranscriptionCandidate(fields[0].toBooleanStrictOrNull() ?: return null,
                fields[1].toIntOrNull() ?: return null, fields[2].toIntOrNull() ?: return null)
            return TranscriptionChoice(candidate, fields[3].toLongOrNull() ?: return null, fields[4])
        }
    }
}

object TranscriptionTuner {
    private const val GPU_RATIO = 0.8

    fun cpuCandidates(processors: Int, threads: Int = 0): List<TranscriptionCandidate> {
        require(processors > 0)
        fun cpu(batch: Int, generation: Int) = TranscriptionCandidate(false, minOf(batch, processors), minOf(generation, processors))
        return if (threads > 0) listOf(cpu(threads, threads)) else listOf(cpu(4, 4), cpu(6, 6), cpu(8, 4)).distinct()
    }

    data class Environment(val weights: String, val projector: String, val runtime: String, val platform: String,
        val cpu: String, val device: String, val fixture: String) {
        fun cacheKey(candidates: List<TranscriptionCandidate>, gpu: Boolean): String =
            listOf(weights, projector, runtime, platform, cpu, device, fixture, candidates, gpu, "1:$GPU_RATIO")
                .joinToString { "${it.toString().length}:$it" }
    }

    data class Sample(val result: Recognition, val nanos: Long)
    class Reference(val text: String, val language: String) {
        private fun normalized(value: String) = java.text.Normalizer.normalize(value, java.text.Normalizer.Form.NFKC)
            .lowercase(java.util.Locale.ROOT).filter { it.isLetterOrDigit() }
        fun matches(result: Recognition) = result.source == LanguageSource.JUDGED && result.lang == language &&
            normalized(result.text) == normalized(text)
    }
    interface Store {
        fun read(key: String): TranscriptionChoice?
        fun write(key: String, choice: TranscriptionChoice)
    }

    suspend fun <T : AutoCloseable> prepare(cpus: List<TranscriptionCandidate>, allowedGpu: Boolean, preference: TranscriptionPreference,
        environment: Environment, reference: Reference, store: Store, beginGpu: () -> Boolean, endGpu: (String?) -> Unit,
        load: suspend (TranscriptionCandidate) -> T, trial: suspend (T) -> Sample): Pair<T, TranscriptionChoice> {
        val gpu = cpus.first().copy(gpu = true)
        val key = environment.cacheKey(cpus, allowedGpu && preference != TranscriptionPreference.CPU)
        val cpuReason = "CPU の試走結果を採用"
        var selectedEngine: T? = null

        suspend fun evaluate(candidate: TranscriptionCandidate, checkEngine: suspend (T) -> TranscriptionChoice): Pair<T, TranscriptionChoice> {
            currentCoroutineContext().ensureActive()
            val engine = load(candidate)
            try {
                val choice = checkEngine(engine)
                currentCoroutineContext().ensureActive()
                return engine to choice
            } catch (error: Throwable) {
                engine.close()
                throw error
            }
        }

        suspend fun ready(candidate: TranscriptionCandidate, cached: TranscriptionChoice? = null) = evaluate(candidate) { engine ->
            val sample = trial(engine)
            check(reference.matches(sample.result)) { "ならし運転の本文または言語が正解と一致しません" }
            cached ?: TranscriptionChoice(candidate, sample.nanos, "GPU を指定")
        }

        suspend fun measure(candidate: TranscriptionCandidate, reason: String) = evaluate(candidate) { engine ->
            trial(engine)
            val samples = List(3) { trial(engine) }
            check(samples.all { reference.matches(it.result) }) { "試走の本文または言語が正解と一致しません" }
            TranscriptionChoice(candidate, samples.map { it.nanos }.sorted()[1], reason)
        }

        fun select(prepared: Pair<T, TranscriptionChoice>): TranscriptionChoice {
            val previous = selectedEngine
            selectedEngine = prepared.first
            previous?.close()
            return prepared.second
        }

        suspend fun fastestCpu(reason: String = cpuReason): TranscriptionChoice {
            var best: TranscriptionChoice? = null
            for (candidate in cpus) {
                val measured = measure(candidate, reason)
                if (best == null || measured.second.medianNanos < best.medianNanos) {
                    best = select(measured)
                } else {
                    measured.first.close()
                }
            }
            return checkNotNull(best)
        }

        fun save(choice: TranscriptionChoice) = choice.also { store.write(key, it) }

        suspend fun cpuOnly(reason: String): TranscriptionChoice {
            val cached = store.read(key)?.takeIf { it.candidate in cpus }
            if (cached == null) return save(fastestCpu(reason))
            return select(ready(cached.candidate, cached.copy(reason = reason)))
        }

        suspend fun tryGpu(attempt: suspend () -> Pair<T, TranscriptionChoice>): Pair<T, TranscriptionChoice>? {
            if (!allowedGpu || !beginGpu()) return null
            var failure: String? = null
            var prepared: Pair<T, TranscriptionChoice>? = null
            try {
                prepared = attempt()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "GPU の準備に失敗しました"
            } finally {
                try {
                    endGpu(failure)
                } catch (error: Throwable) {
                    prepared?.first?.close()
                    throw error
                }
            }
            return prepared
        }

        suspend fun automatic(): TranscriptionChoice {
            if (!allowedGpu) return cpuOnly(cpuReason)
            val cached = store.read(key)
            if (cached != null) {
                if (cached.candidate in cpus) return select(ready(cached.candidate, cached))
                if (cached.candidate == gpu) return tryGpu { ready(gpu, cached) }?.let(::select) ?: fastestCpu()
            }
            val cpu = fastestCpu()
            val measured = tryGpu { measure(gpu, "GPU の試走結果を採用") } ?: return cpu
            if (measured.second.medianNanos <= cpu.medianNanos * GPU_RATIO) return save(select(measured))
            measured.first.close()
            return save(cpu.copy(reason = "GPU は CPU より十分には速くありません"))
        }

        try {
            val choice = when (preference) {
                TranscriptionPreference.GPU -> tryGpu { ready(gpu) }?.let(::select) ?: fastestCpu()
                TranscriptionPreference.CPU -> cpuOnly("CPU を指定")
                TranscriptionPreference.AUTO -> automatic()
            }
            currentCoroutineContext().ensureActive()
            return checkNotNull(selectedEngine) to choice
        } catch (error: Throwable) {
            selectedEngine?.close()
            throw error
        }
    }
}
