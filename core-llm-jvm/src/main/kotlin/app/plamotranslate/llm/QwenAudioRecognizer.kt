package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.speech.RecognitionPriority
import app.plamotranslate.pipeline.speech.QwenTranscript
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.TranscriptionCandidate
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

class QwenAudioRecognizer private constructor(private var handle: Long, private val gpu: Boolean,
    private var loadedThreads: Int, private val loadHandle: (Int) -> Long) : SpeechRecognizer {
    override val judgesFinalTranscript = true
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
                val work = if (priority.urgent) ComputeWork.FinalTranscript else ComputeWork.Regular
                val raw = if (samples.isEmpty()) "" else ProcessCompute.borrow(gpu, loadedThreads, work) { n, _ ->
                    if (loadedThreads != n) {
                        val replacement = loadHandle(n)
                        LlamaRuntime.process.native { LlamaNative.nativeFreeAudio(handle) }
                        handle = replacement
                        loadedThreads = n
                    }
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
            threads: Int, preference: TranscriptionPreference, environment: TranscriptionTuner.Environment,
            store: TranscriptionTuner.Store, beginGpu: () -> Boolean, endGpu: (String?) -> Unit,
        ): Pair<QwenAudioRecognizer, TranscriptionChoice> {
            val topology = ProcessCompute.topology()
            val candidates = topology.candidates(threads).map { TranscriptionCandidate(false, it, it) }
            val stableStore = object : TranscriptionTuner.Store by store {
                override fun write(key: String, choice: TranscriptionChoice) {
                    if (ProcessCompute.topology().key == topology.key) store.write(key, choice)
                }
            }
            return TranscriptionTuner.prepare(candidates, target.useGpu,
                preference, environment.copy(cpu = "${environment.cpu}/${topology.key}"), QwenTrialAudio.reference, stableStore,
                { action -> LlamaRuntime.process.prepare(true, action) }, beginGpu, endGpu, { candidate ->
                    load(File(models, SharedModelCatalog.qwenWeights.path), File(models, SharedModelCatalog.qwenProjector.path),
                        nativeDir, variant, target.copy(requestGpu = candidate.gpu),
                        candidate.batch)
                }, { it.trial(QwenTrialAudio.samples) }, { Logs.i(it) })
        }

        suspend fun load(model: File, projector: File, nativeDir: String, cpuVariant: String, target: InferenceTarget, threads: Int = ProcessCompute.topology().candidates().first()): QwenAudioRecognizer {
            val runtime = LlamaRuntime.process
            return ProcessCompute.borrow(target.useGpu, threads) { count, _ ->
                val gpu = runtime.initialize(nativeDir, cpuVariant, target)
                check(!target.useGpu || gpu) { "GPU を使用できません。CPU 専用で起動した場合は再起動が必要です" }
                fun loadHandle(n: Int) = runtime.native { LlamaNative.nativeLoadAudio(model.absolutePath, projector.absolutePath, gpu, n, n, n) }
                QwenAudioRecognizer(loadHandle(count), gpu, count, ::loadHandle)
            }
        }
    }
}
