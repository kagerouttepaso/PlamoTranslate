package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.ComputeResources
import kotlinx.coroutines.runBlocking

/** ONNX のスレッド数はセッションを作るときに決まる。呼び出し中は同じセッションを保持する。 */
private class SpeechSession<T : AutoCloseable>(private val compute: ComputeResources, val requested: Int, var threads: Int, var model: T, val create: (Int) -> T) {
    @Synchronized fun <R> run(count: Int, action: (T) -> R): R {
        if (threads != count) {
            val replacement = create(count)
            model.close()
            model = replacement
            threads = count
        }
        return action(model)
    }

    suspend fun <R> use(work: ComputeWork, action: (T) -> R): R =
        compute.borrow(false, requested, work) { count, _ -> run(count, action) }
}

// ONNX セッションは貸出中に中断せず作り、広げた CPU 指定を作業スレッドに引き継ぐ。
object ScheduledSpeech {
    private suspend fun <T : AutoCloseable> prepare(compute: ComputeResources, purpose: ThreadBenchmark.Purpose, name: String, version: String,
        directory: java.io.File?, threads: Int, create: (Int) -> T, sample: (T) -> Unit): SpeechSession<T> {
        var trial: T? = null
        var trialCount = 0
        val benchmark = ThreadBenchmark(purpose, name, "$version/${SpeechTrialAudio.version}", directory) { n ->
            if (trialCount != n) {
                trial?.close()
                trial = null
                trial = create(n)
                trialCount = n
            }
            kotlin.system.measureNanoTime { sample(checkNotNull(trial)) }
        }
        try {
            val selected = compute.selectThreads(benchmark, threads)
            return compute.borrow(false, if (threads > 0) threads else selected) { n, _ ->
                val model = trial?.takeIf { n == trialCount }?.also { trial = null } ?: create(n)
                SpeechSession(compute, if (threads > 0) threads else selected, n, model, create)
            }
        } finally {
            trial?.close()
        }
    }

    suspend fun senseVoice(compute: ComputeResources, threads: Int, directory: java.io.File? = null, create: (Int) -> SpeechRecognizer): SpeechRecognizer {
        val session = prepare(compute, ThreadBenchmark.Purpose.Transcription, "SenseVoice", "small-int8-2024-07-17/sherpa-2.0.1", directory, threads, create) { it.recognize(SpeechTrialAudio.samples) }
        return object : SpeechRecognizer by session.model {
            override fun recognize(samples: FloatArray) = recognizeAs(samples, null)
            override fun recognizeAs(samples: FloatArray, language: String?) = runBlocking {
                transcribe(samples, language, RecognitionPriority.PARTIAL)
            }
            override suspend fun transcribe(samples: FloatArray, language: String?, priority: RecognitionPriority): Recognition =
                session.use(priority.work) { it.recognizeAs(samples, language) }
            override fun close() = session.model.close()
        }
    }

    suspend fun identifier(compute: ComputeResources, threads: Int, directory: java.io.File? = null, create: (Int) -> LanguageIdentifier): LanguageIdentifier {
        val session = prepare(compute, ThreadBenchmark.Purpose.LanguageIdentification, "Whisper", "tiny-65176e2d", directory, threads, create) { it.scores(SpeechTrialAudio.samples, null) }
        return object : LanguageIdentifier {
            override fun scores(samples: FloatArray, candidates: Set<String>?) = runBlocking {
                scores(samples, candidates, RecognitionPriority.LANGUAGE)
            }
            override suspend fun scores(samples: FloatArray, candidates: Set<String>?, priority: RecognitionPriority) =
                session.use(priority.work) { it.scores(samples, candidates) }
            override fun close() = session.model.close()
        }
    }
}
