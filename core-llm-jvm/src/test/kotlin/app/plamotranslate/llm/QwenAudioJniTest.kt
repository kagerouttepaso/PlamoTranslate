package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.speech.LanguageSource
import app.plamotranslate.pipeline.speech.RecognitionPriority
import app.plamotranslate.log.LogSink
import app.plamotranslate.log.Logs
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeout
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class QwenAudioJniTest {
    private val directory = System.getenv("MOCKINGWORD_ASR_FIXTURES")?.let(::File)
    private val nativeDir = System.getenv("MOCKINGWORD_ASR_NATIVE")

    private suspend fun load(gpu: Boolean = false): QwenAudioRecognizer {
        assumeTrue("GGUF・16 kHz float32 PCM・JNI の試験環境を指定する", directory != null && nativeDir != null)
        for (name in listOf("ggml-base", "ggml", "llama", "mtmd")) System.load(File(nativeDir, "$name.dll").absolutePath)
        return QwenAudioRecognizer.load(File(directory, "Qwen3-ASR-0.6B-Q8_0.gguf"), File(directory, "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"), nativeDir!!, "", InferenceTarget(true, gpu))
    }

    @Test fun 同梱の試走音声をCPUで書き起こして本文と言語が一致する() = runBlocking {
        load(gpu = false).use { recognizer ->
            val result = recognizer.trial(QwenTrialAudio.samples).result
            println("CPU 試走：$result")
            assertTrue(result.toString(), QwenTrialAudio.reference.matches(result))
        }
    }

    private fun pcm(id: String): FloatArray {
        val buffer = ByteBuffer.wrap(File(directory, "$id.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    private fun cancelAndReuse(gpu: Boolean) = runBlocking {
        load(gpu).use { recognizer ->
            if (gpu) assertTrue(LlamaRuntime.process.initialize(nativeDir!!, "", InferenceTarget(true, true)))
            val samples = pcm("ja00")
            val baseline = recognizer.recognize(samples)
            assertEquals("ja", baseline.lang)
            for (stage in listOf("音声エンコーダー", "音声入力", "文字生成")) {
                val entered = CountDownLatch(1)
                val resume = CountDownLatch(1)
                val stages = mutableListOf<String>()
                Logs.install(listOf(LogSink { _, _, message, _ ->
                    if (message.startsWith("ASR #")) stages += message.substringAfterLast(' ')
                    if (message.startsWith("ASR #") && message.endsWith(" $stage")) {
                        entered.countDown()
                        resume.await(30, TimeUnit.SECONDS)
                    }
                }), false)
                val partial = launch(Dispatchers.IO) { recognizer.transcribe(samples, null, RecognitionPriority.PARTIAL) }
                try {
                    assertTrue(stage, entered.await(30, TimeUnit.SECONDS))
                    partial.cancel()
                } finally {
                    resume.countDown()
                    partial.join()
                    Logs.install(emptyList(), false)
                }
                assertTrue(partial.isCancelled)
                assertEquals(listOf("音声エンコーダー", "音声入力", "文字生成").takeWhile { it != stage } + listOf(stage, "取消完了"), stages)
                assertEquals(stage, baseline, recognizer.recognize(samples))
            }
        }
    }

    @Test fun CPUで各段を中断した後も同じ音声を同じ結果にできる() = cancelAndReuse(false)

    @Test fun Vulkanで各段を中断した後も同じ音声を同じ結果にできる() {
        assumeTrue(System.getenv("MOCKINGWORD_ASR_VULKAN") == "1")
        cancelAndReuse(true)
    }

    @Test(timeout = 30000) fun 解放はGPU待ちの要求を取り消して以後の要求を拒む() = runBlocking {
        assumeTrue(System.getenv("MOCKINGWORD_ASR_VULKAN") == "1")
        val recognizer = LlamaRuntime.process.prepare(true) { load(true) }
        try {
            ProcessCompute.borrow(true, 1) { _, _ ->
                val waiting = launch(start = CoroutineStart.UNDISPATCHED) {
                    recognizer.transcribe(pcm("en00"), null, RecognitionPriority.PARTIAL)
                }
                val close = async(Dispatchers.IO) { recognizer.close() }
                withTimeout(5000) { waiting.join(); close.await() }
                assertTrue(waiting.isCancelled)
            }
            assertThrows(IllegalStateException::class.java) { recognizer.recognize(FloatArray(0)) }
            Unit
        } finally { recognizer.close() }
    }

    @Test fun 三言語の音声を自動判定と指定の両方で書き起こす() = runBlocking {
        load().use { recognizer ->
            for ((code, script) in mapOf("ja" to Regex("[ぁ-んァ-ン]"), "en" to Regex("[A-Za-z]"), "ko" to Regex("[가-힣]"))) {
                for (specified in listOf(null, code)) {
                    val result = recognizer.recognizeAs(pcm("${code}00"), specified)
                    assertEquals(code, result.lang)
                    assertEquals(if (specified == null) LanguageSource.JUDGED else LanguageSource.SPECIFIED, result.source)
                    assertTrue(result.text, script.containsMatchIn(result.text))
                    assertFalse(result.text.contains('\uFFFD') || result.text.contains("<|"))
                }
            }
            assertEquals("", recognizer.recognize(FloatArray(16000)).text)
        }
    }
}
