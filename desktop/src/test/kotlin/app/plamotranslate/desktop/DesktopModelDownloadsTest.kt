package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.ModelFile
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.SpeechModel
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.net.InetSocketAddress
import java.nio.file.Files
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.atomic.AtomicReference

class DesktopModelDownloadsTest {
    @get:Rule val temporary = TemporaryFolder()

    private val content = ByteArray(512 * 1024) { (it % 251).toByte() }
    private val range = AtomicReference<String?>()

    @Test
    fun `容量不足では通信前に停止し以前のファイルを消さない`() = withModelServer { model ->
        val directory = temporary.root.toPath()
        val target = directory.resolve(model.path)
        Files.createDirectories(target.parent)
        Files.write(target, byteArrayOf(42))
        val downloads = DesktopModelDownloads(directory) { 0L }
        assertThrows(IOException::class.java) { runBlocking { downloads.prepare(model.copy(url = "offline:")) {} } }
        assertArrayEquals(byteArrayOf(42), Files.readAllBytes(target))
    }

    @Test
    fun `PC の旧版と片方だけの新版では使用可にしない`() {
        val catalog = SharedModelCatalog
        val directory = temporary.root.toPath()
        val files = DesktopSpeechModels.requiredFiles(SpeechModel.QWEN3_ASR)
        assertTrue(files.containsAll(catalog.qwenGguf))
        assertEquals(catalog.qwenGguf, catalog.recognition(SpeechModel.QWEN3_ASR))
        catalog.qwen3Asr.forEach { file ->
            val target = directory.resolve(file.path)
            Files.createDirectories(target.parent)
            Files.write(target, byteArrayOf(1))
        }
        assertTrue(LegacySpeechFiles(directory).inspect().isEmpty())
        val old = SettingsState(defaultPath = directory, catalogStatus = catalog.qwen3Asr.associateWith { it.size })
        assertFalse(old.hasRecognition(SpeechModel.QWEN3_ASR))
        assertFalse(old.copy(catalogStatus = mapOf(catalog.qwenWeights to catalog.qwenWeights.size)).hasRecognition(SpeechModel.QWEN3_ASR))
        assertTrue(old.copy(catalogStatus = files.associateWith { it.size }).hasRecognition(SpeechModel.QWEN3_ASR))
    }

    @Test
    fun `手動配置は通信せず照合し同じ容量でも異なる内容を拒む`() = withModelServer { model ->
        val directory = temporary.root.toPath()
        val target = directory.resolve(model.path)
        Files.createDirectories(target.parent)
        Files.write(target, content.reversedArray())
        assertFalse(VerifiedSpeechFiles.matches(target, model))
        Files.write(target, content)
        assertTrue(VerifiedSpeechFiles.matches(target, model))
        runBlocking { DesktopModelDownloads(directory).prepare(model.copy(url = "offline:")) {} }
        assertArrayEquals(content, Files.readAllBytes(target))
    }

    @Test
    fun resumesIntoNestedModelDirectory() = withModelServer { model ->
        val directory = temporary.root.toPath()
        val target = directory.resolve(model.path)
        val partial = target.resolveSibling("${target.fileName}.part")
        val offset = 123_456
        Files.createDirectories(target.parent)
        Files.write(partial, content.copyOfRange(0, offset))

        val downloads = DesktopModelDownloads(directory)
        assertEquals(offset.toLong(), downloads.bytesAvailable(model))
        runBlocking { downloads.prepare(model) {} }

        assertEquals("bytes=$offset-", range.get())
        assertArrayEquals(content, Files.readAllBytes(target))
        assertEquals(model.size, downloads.bytesAvailable(model))
        assertFalse(Files.exists(partial))
    }

    @Test
    fun deletesPartialWhenSha256DoesNotMatch() = withModelServer { model ->
        val directory = temporary.root.toPath()
        val badHash = model.copy(sha256 = "0".repeat(64))
        val downloads = DesktopModelDownloads(directory)

        val error = assertThrows(IOException::class.java) {
            runBlocking { downloads.prepare(badHash) {} }
        }

        assertTrue(error.message.orEmpty().contains("SHA-256"))
        assertFalse(Files.exists(directory.resolve(model.path)))
        assertFalse(Files.exists(directory.resolve("${model.path}.part")))
        assertEquals(0L, downloads.bytesAvailable(badHash))
    }

    @Test
    fun cancellationKeepsOnlyPartialAndRetryCompletes() = withModelServer { model ->
        val directory = temporary.root.toPath()
        val downloads = DesktopModelDownloads(directory)
        runBlocking {
            val download = launch {
                val context = currentCoroutineContext()
                downloads.prepare(model) { if (it.bytes > 0) context.cancel() }
            }
            download.join()
            assertTrue(download.isCancelled)
        }
        val target = directory.resolve(model.path)
        val partial = directory.resolve("${model.path}.part")
        val received = Files.size(partial)
        assertTrue(received in 1 until model.size)
        assertFalse(Files.exists(target))

        runBlocking { downloads.prepare(model) {} }

        assertEquals("bytes=$received-", range.get())
        assertArrayEquals(content, Files.readAllBytes(target))
        assertFalse(Files.exists(partial))
    }

    private fun withModelServer(test: (ModelFile) -> Unit) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/model") { exchange ->
            exchange.use {
                val header = exchange.requestHeaders.getFirst("Range")
                range.set(header)
                val offset = header?.removePrefix("bytes=")?.removeSuffix("-")?.toInt() ?: 0
                val status = if (header == null) 200 else 206
                if (status == 206) {
                    exchange.responseHeaders.set("Content-Range", "bytes $offset-${content.lastIndex}/${content.size}")
                }
                exchange.sendResponseHeaders(status, (content.size - offset).toLong())
                try {
                    exchange.responseBody.write(content, offset, content.size - offset)
                } catch (_: IOException) {
                    // 取り消しの試験では、クライアントが受信を途中で閉じる。
                }
            }
        }
        server.start()
        try {
            val sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content))
            test(ModelFile("試験用モデル", "http://127.0.0.1:${server.address.port}/model", "asr/example/model.onnx",
                content.size.toLong(), sha))
        } finally {
            server.stop(0)
        }
    }
}
