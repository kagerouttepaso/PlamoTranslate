package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfMeasure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.Font
import java.awt.font.FontRenderContext
import java.io.ByteArrayInputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Duration
import java.util.HexFormat

/** 表示と書き込みで共用する Noto Sans JP の標準・太字。 */
class PdfFonts private constructor(internal val regular: ByteArray, internal val bold: ByteArray) {
    private val regularFont = awt(regular)
    private val boldFont = awt(bold)
    private val context = FontRenderContext(null, true, true)

    /** PDF のポイントで字幅を返す。 */
    val measure: PdfMeasure = { text, bold, size ->
        val font = if (bold) boldFont else regularFont
        font.getStringBounds(text, context).width * size / 100
    }

    private fun awt(bytes: ByteArray): Font =
        Font.createFont(Font.TRUETYPE_FONT, ByteArrayInputStream(bytes)).deriveFont(100f)

    private data class Source(val file: String, val weight: String, val length: Int, val sha256: String) {
        fun url(): URI = URI.create("https://cdn.jsdelivr.net/npm/@expo-google-fonts/noto-sans-jp@0.4.4/$weight/$file")

        fun matches(bytes: ByteArray): Boolean = bytes.size == length &&
            HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)) == sha256
    }

    companion object {
        private val regular = Source("NotoSansJP_400Regular.ttf", "400Regular", 5731428,
            "6255ea4692e97ef27330304fb96fd650f9111f868cd585fcf2802d42823930e5")
        private val bold = Source("NotoSansJP_700Bold.ttf", "700Bold", 5726432,
            "7c03e14d8a03e8f5181dbd130712d51e4ae01ac648cbd59442ae8b16c0951835")
        private val cache = Mutex()
        private val client by lazy { HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build() }

        /**
         * 固定版を初回に取得する。保存済みのフォントも長さと SHA-256 を照合する。
         * [onProgress] は読み込んだバイト数を入出力スレッドで受け取る。
         */
        suspend fun load(
            directory: Path = Path.of(checkNotNull(System.getenv("LOCALAPPDATA")), "Mockingword", "fonts"),
            onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
        ): PdfFonts = withContext(Dispatchers.IO) {
            cache.withLock {
                Files.createDirectories(directory)
                val length = (regular.length + bold.length).toLong()
                onProgress(0, length)
                val regularBytes = fontBytes(directory, regular) { onProgress(it.toLong(), length) }
                onProgress(regular.length.toLong(), length)
                val boldBytes = fontBytes(directory, bold) { onProgress(regular.length.toLong() + it, length) }
                onProgress(length, length)
                PdfFonts(regularBytes, boldBytes)
            }
        }

        private fun fontBytes(directory: Path, source: Source, progress: (Int) -> Unit): ByteArray {
            val file = directory.resolve(source.file)
            if (Files.isRegularFile(file) && Files.size(file) == source.length.toLong()) {
                val bytes = Files.readAllBytes(file)
                if (source.matches(bytes)) return bytes
            }
            val request = HttpRequest.newBuilder(source.url()).timeout(Duration.ofMinutes(2)).build()
            val response = client.send(request, HttpResponse.BodyHandlers.ofInputStream())
            val bytes = response.body().use { input ->
                check(response.statusCode() == 200) { "PDF 用フォントを取得できません（HTTP ${response.statusCode()}）" }
                val data = ByteArray(source.length)
                var read = 0
                while (read < data.size) {
                    val count = input.readNBytes(data, read, minOf(256 * 1024, data.size - read))
                    if (count == 0) break
                    read += count
                    progress(read)
                }
                check(read == data.size && input.read() == -1) { "取得した PDF 用フォントのサイズが違います" }
                data
            }
            check(source.matches(bytes)) { "取得した PDF 用フォントが固定した版と一致しません" }
            val temporary = Files.createTempFile(directory, source.file, ".part")
            try {
                Files.write(temporary, bytes)
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temporary)
            }
            return bytes
        }
    }
}
