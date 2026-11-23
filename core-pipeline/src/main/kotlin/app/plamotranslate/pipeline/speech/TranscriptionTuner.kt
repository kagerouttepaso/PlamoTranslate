package app.plamotranslate.pipeline.speech

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class TranscriptionPreference { AUTO, CPU, GPU }
data class TranscriptionCandidate(val gpu: Boolean, val batch: Int, val generation: Int)
data class TranscriptionChoice(val candidate: TranscriptionCandidate, val medianNanos: Long, val reason: String)

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
        val compareGpu = allowedGpu && preference != TranscriptionPreference.CPU
        val key = environment.cacheKey(cpus, compareGpu)
        val automatic = preference == TranscriptionPreference.AUTO
        val cached = if (automatic) store.read(key)?.takeIf { it.candidate in cpus || compareGpu && it.candidate == gpu } else null
        suspend fun measure(candidate: TranscriptionCandidate, reason: String): TranscriptionChoice = load(candidate).use { engine ->
            trial(engine)
            val samples = List(3) { trial(engine) }
            check(samples.all { reference.matches(it.result) }) { "試走の本文または言語が正解と一致しません" }
            TranscriptionChoice(candidate, samples.map { it.nanos }.sorted()[1], reason)
        }
        suspend fun ready(choice: TranscriptionChoice, save: Boolean): Pair<T, TranscriptionChoice> {
            val engine = load(choice.candidate)
            try {
                check(reference.matches(trial(engine).result)) { "ならし運転の本文または言語が正解と一致しません" }
                currentCoroutineContext().ensureActive()
                if (save) store.write(key, choice)
                return engine to choice
            } catch (error: Throwable) { engine.close(); throw error }
        }
        if (cached != null && !cached.candidate.gpu) return ready(cached, false)
        suspend fun fastestCpu() = cpus.map { measure(it, "CPU の試走結果を採用") }.minBy { it.medianNanos }
        val cpu = if (cached == null && (!compareGpu || preference != TranscriptionPreference.GPU)) fastestCpu() else null
        var saveCpu = automatic && !compareGpu
        if (compareGpu && beginGpu()) {
            var failure: String? = null
            try {
                if (cached != null) return ready(cached, false)
                val measured = measure(gpu, if (automatic) "GPU の試走結果を採用" else "GPU を指定")
                if (cpu == null || measured.medianNanos <= cpu.medianNanos * GPU_RATIO) return ready(measured, automatic)
                saveCpu = automatic
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "GPU の準備に失敗しました" }
            finally { endGpu(failure) }
        }
        return ready(cpu ?: fastestCpu(), saveCpu)
    }
}
