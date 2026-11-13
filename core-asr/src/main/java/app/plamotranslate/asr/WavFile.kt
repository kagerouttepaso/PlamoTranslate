package app.plamotranslate.asr

import app.plamotranslate.pipeline.speech.SpeechEngine
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 動作確認で、マイクの代わりに流す音声ファイルを読み書きする。16kHz・モノラル・16bit PCMのWAVだけ扱う。
 * 書くのは、デバッグ版の会話翻訳が聞いた音声を残して、流し直すため（docs/設計/会話翻訳.md「動作確認」）。
 */
object WavFile {
    fun read(file: File): FloatArray = parse(file.readBytes(), file.name)

    /** 少しずつ書き足す。閉じたときに長さを書き込む（閉じる前に落ちて長さが0のままでも、[read] は末尾まで読む）。 */
    class Writer(file: File) : AutoCloseable {
        private val out = RandomAccessFile(file, "rw").apply {
            setLength(0)
            write(header(0))
        }
        private var bytes = 0

        fun write(samples: FloatArray) {
            val buf = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
            for (s in samples) buf.putShort((s.coerceIn(-1f, 1f) * 32767f).toInt().toShort())
            out.write(buf.array())
            bytes += samples.size * 2
        }

        override fun close() {
            out.seek(0)
            out.write(header(bytes))
            out.close()
        }
    }

    /** 16kHz・モノラル・16bit の WAV の頭（44バイト）。[data] は音声のバイト数。 */
    internal fun header(data: Int): ByteArray =
        ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(RIFF); putInt(36 + data); putInt(WAVE)
            putInt(FMT); putInt(16); putShort(1); putShort(1)
            putInt(SpeechEngine.SAMPLE_RATE); putInt(SpeechEngine.SAMPLE_RATE * 2); putShort(2); putShort(16)
            putInt(DATA); putInt(data)
        }.array()

    internal fun parse(bytes: ByteArray, name: String = "WAV"): FloatArray {
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(bytes.size >= 12 && buf.getInt(0) == RIFF && buf.getInt(8) == WAVE) { "WAVではありません: $name" }
        var pos = 12
        var channels = 0
        var rate = 0
        var bits = 0
        while (pos + 8 <= bytes.size) {
            val id = buf.getInt(pos)
            val size = buf.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                FMT -> {
                    channels = buf.getShort(body + 2).toInt()
                    rate = buf.getInt(body + 4)
                    bits = buf.getShort(body + 14).toInt()
                }
                DATA -> {
                    require(channels == 1 && rate == SpeechEngine.SAMPLE_RATE && bits == 16) {
                        "16kHz・モノラル・16bitのWAVだけ読めます（$name：${rate}Hz・${channels}ch・${bits}bit）"
                    }
                    // 長さが0なら、書いている途中で落ちたファイルとみなして末尾まで読む
                    val n = (if (size == 0) bytes.size - body else minOf(size, bytes.size - body)) / 2
                    return FloatArray(n) { buf.getShort(body + it * 2) / 32768f }
                }
            }
            pos = body + size + (size and 1)
        }
        error("WAVに音声データがありません: $name")
    }

    // チャンクIDをリトルエンディアンのIntとして読んだ値
    private const val RIFF = 0x46464952 // "RIFF"
    private const val WAVE = 0x45564157 // "WAVE"
    private const val FMT = 0x20746d66 // "fmt "
    private const val DATA = 0x61746164 // "data"
}
