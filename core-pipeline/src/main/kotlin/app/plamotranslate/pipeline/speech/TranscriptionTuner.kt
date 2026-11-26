package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.compute.ThreadTrials
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

    data class Environment(val weights: String, val projector: String, val runtime: String, val platform: String,
        val cpu: String, val device: String, val fixture: String) {
        fun cacheKey(candidates: List<TranscriptionCandidate>, gpu: Boolean): String =
            listOf(weights, projector, runtime, platform, cpu, device, fixture, candidates, gpu, ThreadBenchmark.SEARCH_VERSION, "5:$GPU_RATIO")
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
        environment: Environment, board: app.plamotranslate.pipeline.compute.ThreadTrialBoard, reference: Reference, store: Store,
        withGpuPreparation: suspend (suspend () -> Unit) -> Unit, beginGpu: () -> Boolean, endGpu: (String?) -> Unit,
        load: suspend (TranscriptionCandidate) -> T, trial: suspend (T) -> Sample, log: (String) -> Unit = {},
        directory: java.io.File? = null, range: IntRange = 1..cpus.maxOf { it.batch }, valid: () -> Boolean = { true }): Pair<T, TranscriptionChoice> {
        val gpu = cpus.first().copy(gpu = true)
        val key = environment.cacheKey(cpus, allowedGpu && preference != TranscriptionPreference.CPU) + "/$range"
        fun cpu(count: Int) = TranscriptionCandidate(false, count, count)
        fun isCpu(candidate: TranscriptionCandidate) = candidate.batch in range && candidate == cpu(candidate.batch)
        val cpuReason = "CPU の試走結果を採用"
        var selectedEngine: T? = null

        suspend fun <R> evaluate(candidate: TranscriptionCandidate, checkEngine: suspend (T) -> R): Pair<T, R> {
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

        var trials = ThreadTrials()
        suspend fun measure(candidate: TranscriptionCandidate, reason: String) = evaluate(candidate) { engine ->
            trials.measure(candidate.batch, {
                val sample = trial(engine)
                check(reference.matches(sample.result)) { "試走の本文または言語が正解と一致しません" }
                sample.nanos
            }) { log("書き起こし $candidate $it") }
                ?.let { TranscriptionChoice(candidate, it, reason) }
        }

        fun select(prepared: Pair<T, TranscriptionChoice>): TranscriptionChoice {
            val previous = selectedEngine
            selectedEngine = prepared.first
            previous?.close()
            return prepared.second
        }

        suspend fun fastestCpu(reason: String = cpuReason): TranscriptionChoice {
            var current: T? = null
            var retained: TranscriptionCandidate? = null
            val benchmark = ThreadBenchmark(ThreadBenchmark.Purpose.Transcription, "Qwen3-ASR", key, directory) {
                val sample = trial(checkNotNull(current))
                check(reference.matches(sample.result)) { "試走の本文または言語が正解と一致しません" }
                sample.nanos
            }
            val curve = benchmark.select(board, environment.cpu, cpus.map { it.batch }, valid, range) { measurements, count ->
                val candidate = cpu(count)
                val (engine, median) = evaluate(candidate) {
                    current = it
                    measurements.measure(count, { benchmark.sample(count) }) { message -> log("Qwen3-ASR CPU $message") }
                }
                current = null
                if (median != null && measurements.preferred() == count) {
                    retained = candidate
                    select(engine to TranscriptionChoice(candidate, median, reason))
                } else engine.close()
            }
            trials = ThreadTrials.decode(curve.encode())
            val count = curve.preferred()
            val candidate = cpu(count)
            val choice = TranscriptionChoice(candidate, curve.median(count), reason)
            if (retained != candidate) {
                selectedEngine?.close()
                selectedEngine = null
                select(ready(candidate, choice))
            }
            return choice
        }

        fun save(choice: TranscriptionChoice) = choice.also { store.write(key, it) }

        suspend fun cpuOnly(reason: String): TranscriptionChoice {
            val cached = store.read(key)?.takeIf { isCpu(it.candidate) }
            if (cached == null) return save(fastestCpu(reason))
            return select(ready(cached.candidate, cached.copy(reason = reason)))
        }

        suspend fun <R> tryGpu(attempt: suspend () -> Pair<T, R>): Pair<T, R>? {
            if (!allowedGpu) return null
            var prepared: Pair<T, R>? = null
            withGpuPreparation {
                if (!beginGpu()) return@withGpuPreparation
                var failure: String? = null
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
            }
            return prepared
        }

        suspend fun automatic(): TranscriptionChoice {
            if (!allowedGpu) return cpuOnly(cpuReason)
            val cached = store.read(key)
            if (cached != null) {
                if (isCpu(cached.candidate)) return select(ready(cached.candidate, cached))
                if (cached.candidate == gpu) return tryGpu { ready(gpu, cached) }?.let(::select) ?: fastestCpu()
            }
            val cpu = fastestCpu()
            val fastestCpuTime = trials.fastestMedian
            val (engine, choice) = tryGpu { measure(gpu, "GPU の試走結果を採用") } ?: return cpu
            if (choice != null && choice.medianNanos <= fastestCpuTime * GPU_RATIO) return save(select(engine to choice))
            engine.close()
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
