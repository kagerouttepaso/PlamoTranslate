package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.speech.QwenTranscript
import app.plamotranslate.pipeline.speech.Recognition
import app.plamotranslate.pipeline.speech.SpeechRecognizer
import kotlinx.coroutines.runBlocking
import java.io.File

class QwenAudioRecognizer private constructor(private var handle: Long, private val gpu: Boolean) : SpeechRecognizer {
    data class Threads(val encoder: Int = 6, val batch: Int = 6, val generation: Int = 6) {
        init { require(encoder > 0 && batch > 0 && generation > 0) }
    }

    override fun recognize(samples: FloatArray): Recognition = recognizeAs(samples, null)

    @Synchronized override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
        check(handle != 0L)
        val name = language?.let { QwenTranscript.names.getValue(it) }.orEmpty()
        val raw = if (samples.isEmpty()) "" else runBlocking {
            LlamaRuntime.process.onDevice(gpu) {
                LlamaRuntime.process.native { LlamaNative.nativeTranscribe(handle, samples, name) }.decodeToString()
            }
        }
        return QwenTranscript.parse(raw, language)
    }

    @Synchronized override fun close() {
        if (handle != 0L) LlamaRuntime.process.native { LlamaNative.nativeFreeAudio(handle) }
        handle = 0L
    }

    companion object {
        fun load(model: File, projector: File, nativeDir: String, cpuVariant: String, target: InferenceTarget, threads: Threads = Threads()): QwenAudioRecognizer = runBlocking {
            val runtime = LlamaRuntime.process
            runtime.prepare(target.useGpu) {
                val gpu = runtime.initialize(nativeDir, cpuVariant, target)
                runtime.onDevice(gpu) {
                    val handle = runtime.native { LlamaNative.nativeLoadAudio(model.absolutePath, projector.absolutePath, gpu, threads.encoder, threads.batch, threads.generation) }
                    QwenAudioRecognizer(handle, gpu)
                }
            }
        }
    }
}
