package app.plamotranslate.asr

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AsrTest {
    @Test
    fun 言語タグからコードを取り出す() {
        assertEquals("ja", SenseVoiceRecognizer.parseLang("<|ja|>"))
        assertEquals("ko", SenseVoiceRecognizer.parseLang("<|ko|>"))
        assertEquals("en", SenseVoiceRecognizer.parseLang("en"))
    }

    @Test
    fun 判定なしはnull() {
        assertNull(SenseVoiceRecognizer.parseLang("<|nospeech|>"))
        assertNull(SenseVoiceRecognizer.parseLang(""))
        assertNull(SenseVoiceRecognizer.parseLang(null))
    }

    @Test
    fun 日本語の語の間の空白を取る() {
        assertEquals("うちの中学は弁当制で", SenseVoiceRecognizer.removeCjkSpaces("うち の 中学 は 弁当 制で", "ja"))
        assertEquals("場合は、50円の学校", SenseVoiceRecognizer.removeCjkSpaces("場 合は、50円 の学校", "ja"))
        assertEquals("iPhone 16を買う", SenseVoiceRecognizer.removeCjkSpaces("iPhone 16 を 買う", "ja"))
    }

    @Test
    fun 韓国語と英語の空白は残す() {
        assertEquals("훨씬 편할 거야", SenseVoiceRecognizer.removeCjkSpaces("훨씬 편할 거야", "ko"))
        assertEquals("Thank you", SenseVoiceRecognizer.removeCjkSpaces("Thank you", "en"))
    }

    @Test
    fun WAVの音声を読む() {
        val samples = shortArrayOf(0, 16384, -32768)
        val wav = wav(samples)
        assertArrayEquals(floatArrayOf(0f, 0.5f, -1f), WavFile.parse(wav), 1e-6f)
    }

    @Test
    fun 書いたWAVを読み戻す() {
        val file = File.createTempFile("rec", ".wav").apply { deleteOnExit() }
        WavFile.Writer(file).use { w ->
            w.write(floatArrayOf(0f, 0.5f))
            w.write(floatArrayOf(-0.5f))
        }
        assertArrayEquals(floatArrayOf(0f, 0.5f, -0.5f), WavFile.read(file), 1e-4f)
    }

    @Test
    fun 書いている途中で長さが0のままのWAVも末尾まで読む() {
        val bytes = WavFile.header(0) + wav(shortArrayOf(16384, -16384)).copyOfRange(44, 48)
        assertArrayEquals(floatArrayOf(0.5f, -0.5f), WavFile.parse(bytes), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun 形式が違うWAVは読まない() {
        WavFile.parse(wav(shortArrayOf(0), rate = 44_100))
    }

    private fun wav(samples: ShortArray, rate: Int = 16_000): ByteArray {
        val data = samples.size * 2
        return ByteBuffer.allocate(44 + data).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + data); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1); putInt(rate); putInt(rate * 2)
            putShort(2); putShort(16)
            put("data".toByteArray()); putInt(data)
            samples.forEach { putShort(it) }
        }.array()
    }
}
