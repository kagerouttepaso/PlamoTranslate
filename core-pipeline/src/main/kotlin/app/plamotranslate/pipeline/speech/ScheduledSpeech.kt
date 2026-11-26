package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.ComputeResources
import kotlinx.coroutines.runBlocking

/** ONNX のスレッド数はセッションを作るときに決まる。呼び出し中は同じセッションを保持する。 */
private class SpeechSession<T : AutoCloseable>(private val compute: ComputeResources, val requested: Int, private var threads: Int,
    initialModel: T, private val create: (Int) -> T) : AutoCloseable {
    var model: T? = initialModel
        private set
    private var closed = false

    @Synchronized fun <R> run(count: Int, action: (T) -> R): R {
        check(!closed) { "認識セッションは閉じています" }
        if (threads != count) closeModel()
        val current = model ?: create(count).also {
            model = it
            threads = count
        }
        return action(current)
    }

    suspend fun <R> use(work: ComputeWork, action: (T) -> R): R =
        compute.borrow(false, requested, work) { count, _ -> run(count, action) }

    @Synchronized override fun close() {
        closed = true
        closeModel()
    }

    private fun closeModel() {
        model?.close()
        model = null
    }
}

// ONNX セッションは貸出中に中断せず作り、広げた CPU 指定を作業スレッドに引き継ぐ。
object ScheduledSpeech {
    private suspend fun <T : AutoCloseable> prepare(compute: ComputeResources, purpose: ThreadBenchmark.Purpose, name: String, version: String,
        directory: java.io.File?, threads: Int, create: (Int) -> T, reserved: Int = 0, sample: (T) -> Unit): SpeechSession<T> {
        var trial: T? = null
        var trialCount = 0
        val benchmark = ThreadBenchmark(purpose, name, "$version/${SpeechTrialAudio.version}", directory) { n ->
            if (trial == null || trialCount != n) {
                trial?.close()
                trial = null
                trial = create(n)
                trialCount = n
            }
            kotlin.system.measureNanoTime { sample(checkNotNull(trial)) }
        }
        var prepared: SpeechSession<T>? = null
        try {
            val selected = compute.selectThreads(benchmark, threads, reserved = reserved)
            return compute.borrow(false, if (threads > 0) threads else selected) { n, _ ->
                // モデルは同時に1つまで持つ（試走で作った数と違えば、閉じてから作る）
                val model = trial?.takeIf { n == trialCount }?.also { trial = null } ?: run {
                    trial?.close()
                    trial = null
                    create(n)
                }
                SpeechSession(compute, if (threads > 0) threads else selected, n, model, create).also { prepared = it }
            }
        } catch (error: Throwable) {
            prepared?.close()
            throw error
        } finally {
            trial?.close()
        }
    }

    suspend fun senseVoice(compute: ComputeResources, threads: Int, directory: java.io.File? = null, create: (Int) -> SpeechRecognizer): SpeechRecognizer {
        val session = prepare(compute, ThreadBenchmark.Purpose.Transcription, "SenseVoice", "small-int8-2024-07-17/sherpa-2.0.1", directory, threads, create) { it.recognize(SpeechTrialAudio.samples) }
        return object : SpeechRecognizer by checkNotNull(session.model) {
            override val cpuThreads get() = session.requested
            override fun recognize(samples: FloatArray) = recognizeAs(samples, null)
            override fun recognizeAs(samples: FloatArray, language: String?) = runBlocking {
                transcribe(samples, language, RecognitionPriority.PARTIAL)
            }
            override suspend fun transcribe(samples: FloatArray, language: String?, priority: RecognitionPriority): Recognition =
                session.use(priority.work) { it.recognizeAs(samples, language) }
            override fun close() = session.close()
        }
    }

    /** 話し中の言語の識別は途中の書き起こしと同時に走るので、書き起こしが借りる [transcriberThreads] を残して数を選ぶ。 */
    suspend fun identifier(compute: ComputeResources, threads: Int, directory: java.io.File? = null, transcriberThreads: Int = 0,
        create: (Int) -> LanguageIdentifier): LanguageIdentifier {
        val session = prepare(compute, ThreadBenchmark.Purpose.LanguageIdentification, "Whisper", "tiny-65176e2d", directory, threads, create,
            transcriberThreads) { it.scores(SpeechTrialAudio.samples, null) }
        return object : LanguageIdentifier {
            override fun scores(samples: FloatArray, candidates: Set<String>?) = runBlocking {
                scores(samples, candidates, RecognitionPriority.LANGUAGE)
            }
            override suspend fun scores(samples: FloatArray, candidates: Set<String>?, priority: RecognitionPriority) =
                session.use(priority.work) { it.scores(samples, candidates) }
            override fun close() = session.close()
        }
    }
}
