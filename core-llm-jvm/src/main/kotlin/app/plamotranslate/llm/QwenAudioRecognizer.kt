package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.diagnostics.GpuWork
import app.plamotranslate.pipeline.speech.RecognitionPriority
import app.plamotranslate.pipeline.speech.QwenTranscript
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.TranscriptionChoice
import app.plamotranslate.pipeline.speech.TranscriptionPreference
import app.plamotranslate.pipeline.speech.TranscriptionTuner
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import java.io.File

class QwenAudioRecognizer private constructor(private val handle: Long, private val gpu: Boolean) : SpeechRecognizer {
    override val judgesFinalTranscript = true
    data class Threads(val encoder: Int = 6, val batch: Int = 6, val generation: Int = 6) {
        init { require(encoder > 0 && batch > 0 && generation > 0) }
    }

    override fun recognize(samples: FloatArray): Recognition = recognizeAs(samples, null)

    private val execution = Mutex()
    private val requests = mutableSetOf<Job>()
    private var closing = false

    override fun recognizeAs(samples: FloatArray, language: String?): Recognition = runBlocking {
        transcribe(samples, language, RecognitionPriority.PARTIAL)
    }

    override suspend fun transcribe(samples: FloatArray, language: String?, priority: RecognitionPriority): Recognition =
        run(samples, language, priority) {}

    suspend fun trial(samples: FloatArray, clock: () -> Long = System::nanoTime): TranscriptionTuner.Sample {
        var started = 0L
        val result = run(samples, null, RecognitionPriority.PARTIAL) { started = clock() }
        return TranscriptionTuner.Sample(result, clock() - started)
    }

    private suspend fun run(samples: FloatArray, language: String?, priority: RecognitionPriority, started: () -> Unit): Recognition = coroutineScope {
        val job = coroutineContext.job
        synchronized(requests) {
            check(!closing) { "認識器は閉じています" }
            requests.add(job)
        }
        try {
            val name = language?.let { QwenTranscript.names.getValue(it) }.orEmpty()
            execution.withLock {
                val work = if (priority.urgent) GpuWork.FinalTranscript else GpuWork.Regular
                val raw = if (samples.isEmpty()) "" else LlamaRuntime.process.onDevice(gpu, work) {
                    coroutineContext.ensureActive()
                    started()
                    suspendCancellableCoroutine<ByteArray> { result ->
                        LlamaRuntime.process.native {
                            val request = LlamaNative.nativeNewAudioRequest()
                            result.invokeOnCancellation { LlamaNative.nativeCancel(request) }
                            if (result.isActive) {
                                val bytes = LlamaNative.nativeTranscribe(handle, request, samples, name)
                                if (bytes == null) result.cancel(CancellationException("書き起こしを取り消しました"))
                                else result.resume(bytes)
                            }
                        }
                    }.decodeToString()
                }
                QwenTranscript.parse(raw, language)
            }
        } finally {
            synchronized(requests) { requests.remove(job) }
        }
    }

    @Synchronized override fun close() {
        val active = synchronized(requests) {
            if (closing) return
            closing = true
            requests.toList()
        }
        active.forEach { it.cancel() }
        runBlocking { active.forEach { it.join() } }
        LlamaRuntime.process.native { LlamaNative.nativeFreeAudio(handle) }
    }

    companion object {
        /** 両 OS の試走とならし運転で同じ音声、候補、実装を使う。 */
        suspend fun prepare(
            models: File, nativeDir: String, variant: String, target: InferenceTarget,
            processors: Int, threads: Int, preference: TranscriptionPreference, environment: TranscriptionTuner.Environment,
            store: TranscriptionTuner.Store, beginGpu: () -> Boolean, endGpu: (String?) -> Unit,
        ): Pair<QwenAudioRecognizer, TranscriptionChoice> =
            TranscriptionTuner.prepare(TranscriptionTuner.cpuCandidates(processors, threads), target.useGpu,
                preference, environment, QwenTrialAudio.reference, store, beginGpu, endGpu, { candidate ->
                    load(File(models, SharedModelCatalog.qwenWeights.path), File(models, SharedModelCatalog.qwenProjector.path),
                        nativeDir, variant, target.copy(requestGpu = candidate.gpu),
                        Threads(candidate.batch, candidate.batch, candidate.generation))
                }, { it.trial(QwenTrialAudio.samples) })

        suspend fun load(model: File, projector: File, nativeDir: String, cpuVariant: String, target: InferenceTarget, threads: Threads = Threads()): QwenAudioRecognizer {
            val runtime = LlamaRuntime.process
            return runtime.onDevice(target.useGpu) {
                val gpu = runtime.initialize(nativeDir, cpuVariant, target)
                check(!target.useGpu || gpu) { "GPU を使用できません。CPU 専用で起動した場合は再起動が必要です" }
                val handle = runtime.native { LlamaNative.nativeLoadAudio(model.absolutePath, projector.absolutePath, gpu, threads.encoder, threads.batch, threads.generation) }
                QwenAudioRecognizer(handle, gpu)
            }
        }
    }
}
