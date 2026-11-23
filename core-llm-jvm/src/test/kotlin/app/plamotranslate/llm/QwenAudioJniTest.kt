package app.plamotranslate.llm

import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import app.plamotranslate.pipeline.speech.LanguageSource
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test

class QwenAudioJniTest {
    private val directory = System.getenv("MOCKINGWORD_ASR_FIXTURES")?.let(::File)
    private val nativeDir = System.getenv("MOCKINGWORD_ASR_NATIVE")

    private fun load(gpu: Boolean = false): QwenAudioRecognizer {
        assumeTrue("GGUF・16 kHz float32 PCM・JNI の試験環境を指定する", directory != null && nativeDir != null)
        for (name in listOf("ggml-base", "ggml", "llama", "mtmd")) System.load(File(nativeDir, "$name.dll").absolutePath)
        return QwenAudioRecognizer.load(File(directory, "Qwen3-ASR-0.6B-Q8_0.gguf"), File(directory, "mmproj-Qwen3-ASR-0.6B-Q8_0.gguf"), nativeDir!!, "", InferenceTarget(true, gpu))
    }

    private fun pcm(id: String): FloatArray {
        val buffer = ByteBuffer.wrap(File(directory, "$id.pcm").readBytes()).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return FloatArray(buffer.remaining()).also { buffer.get(it) }
    }

    @Test fun 三言語の音声を自動判定と指定の両方で書き起こす() {
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
