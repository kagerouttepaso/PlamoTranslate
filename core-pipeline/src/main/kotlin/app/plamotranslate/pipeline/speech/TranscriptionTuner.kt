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
        suspend fun ready(choice: TranscriptionChoice): Pair<T, TranscriptionChoice> {
            val engine = load(choice.candidate)
            try {
                check(reference.matches(trial(engine).result)) { "ならし運転の本文または言語が正解と一致しません" }
                currentCoroutineContext().ensureActive()
                return engine to choice
            } catch (error: Throwable) { engine.close(); throw error }
        }
        fun save(prepared: Pair<T, TranscriptionChoice>): Pair<T, TranscriptionChoice> {
            try { store.write(key, prepared.second) } catch (error: Throwable) { prepared.first.close(); throw error }
            return prepared
        }
        if (cached != null && !cached.candidate.gpu) return ready(cached)
        suspend fun fastestCpu() = cpus.map { measure(it, if (preference == TranscriptionPreference.CPU) "CPU を指定" else "CPU の試走結果を採用") }.minBy { it.medianNanos }
        val cpu = if (cached == null && (!compareGpu || preference != TranscriptionPreference.GPU)) fastestCpu() else null
        var saveCpu = automatic && !compareGpu
        if (compareGpu && beginGpu()) {
            var failure: String? = null
            var prepared: Pair<T, TranscriptionChoice>? = null
            try {
                val measured = cached ?: measure(gpu, if (automatic) "GPU の試走結果を採用" else "GPU を指定")
                if (cpu == null || measured.medianNanos <= cpu.medianNanos * GPU_RATIO) prepared = ready(measured)
                else saveCpu = automatic
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { failure = error.message ?: "GPU の準備に失敗しました" }
            finally {
                try { endGpu(failure) } catch (error: Throwable) { prepared?.first?.close(); throw error }
            }
            prepared?.let { return if (automatic && cached == null) save(it) else it }
        }
        val selected = cpu ?: fastestCpu()
        val prepared = ready(if (saveCpu && compareGpu) selected.copy(reason = "GPU は CPU より十分には速くありません") else selected)
        return if (saveCpu) save(prepared) else prepared
    }
}
