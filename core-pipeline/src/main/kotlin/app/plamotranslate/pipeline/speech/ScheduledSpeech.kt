package app.plamotranslate.pipeline.speech

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

object ScheduledSpeech {
    suspend fun senseVoice(compute: ComputeResources, threads: Int, create: (Int) -> SpeechRecognizer): SpeechRecognizer {
        val session = compute.borrow(false, threads) { n, _ -> SpeechSession(compute, threads, n, create(n), create) }
        return object : SpeechRecognizer by session.model {
            override fun recognize(samples: FloatArray) = recognizeAs(samples, null)
            override fun recognizeAs(samples: FloatArray, language: String?) = runBlocking {
                transcribe(samples, language, RecognitionPriority.PARTIAL)
            }
            override suspend fun transcribe(samples: FloatArray, language: String?, priority: RecognitionPriority): Recognition =
                session.use(if (priority.urgent) ComputeWork.FinalTranscript else ComputeWork.Regular) { it.recognizeAs(samples, language) }
            override fun close() = session.model.close()
        }
    }

    suspend fun identifier(compute: ComputeResources, threads: Int, create: (Int) -> LanguageIdentifier): LanguageIdentifier {
        val session = compute.borrow(false, threads) { n, _ -> SpeechSession(compute, threads, n, create(n), create) }
        return object : LanguageIdentifier {
            override fun scores(samples: FloatArray, candidates: Set<String>?) = runBlocking {
                session.use(ComputeWork.Regular) { it.scores(samples, candidates) }
            }
            override fun close() = session.model.close()
        }
    }
}
