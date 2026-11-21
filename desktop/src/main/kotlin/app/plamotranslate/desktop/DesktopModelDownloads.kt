package app.plamotranslate.desktop

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.HexFormat

internal data class ModelDownloadProgress(val model: DesktopModelCatalog, val bytes: Long, val verifying: Boolean = false)

internal class DesktopModelDownloads(private val directory: Path) {
    fun status(): Map<DesktopModelCatalog, Long> = DesktopModelCatalog.entries.associateWith { model ->
        if (sizeOf(model.path(directory)) == model.size) model.size else sizeOf(partial(model)).coerceIn(0L, model.size - 1)
    }

    suspend fun prepare(model: DesktopModelCatalog, publish: (ModelDownloadProgress) -> Unit) {
        val target = model.path(directory)
        if (sizeOf(target) == model.size) {
            publish(ModelDownloadProgress(model, model.size, verifying = true))
            if (verify(target, model)) return
        }

        Files.createDirectories(directory)
        val partial = partial(model)
        val offset = sizeOf(partial).takeIf { it in 1..model.size } ?: 0L
        if (offset < model.size) transfer(model, partial, offset, publish)

        publish(ModelDownloadProgress(model, model.size, verifying = true))
        if (!verify(partial, model)) {
            Files.deleteIfExists(partial)
            throw IOException("モデルの SHA-256 が一致しません。もう一度ダウンロードしてください。")
        }
        currentCoroutineContext().ensureActive()
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }

    private fun partial(model: DesktopModelCatalog): Path = directory.resolve("${model.filename}.part")

    private suspend fun transfer(
        model: DesktopModelCatalog,
        partial: Path,
        offset: Long,
        publish: (ModelDownloadProgress) -> Unit,
    ) {
        val context = currentCoroutineContext()
        val connection = (URI.create(model.url).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 5_000
            setRequestProperty("Accept-Encoding", "identity")
            if (offset > 0) setRequestProperty("Range", "bytes=$offset-")
        }
        try {
            val status = connection.responseCode
            val append = when (status) {
                HttpURLConnection.HTTP_OK -> false
                HttpURLConnection.HTTP_PARTIAL -> {
                    val expected = "bytes $offset-${model.size - 1}/${model.size}"
                    if (connection.getHeaderField("Content-Range") != expected || offset == 0L) {
                        throw IOException("再開位置が一致しません。")
                    }
                    true
                }
                else -> throw IOException("モデルをダウンロードできませんでした（HTTP $status）。")
            }
            var bytes = if (append) offset else 0L
            val remaining = model.size - bytes
            if (connection.contentLengthLong >= 0L && connection.contentLengthLong != remaining) {
                throw IOException("配布元のファイルの大きさがモデル一覧と一致しません。")
            }
            context.ensureActive()
            val mode = if (append) StandardOpenOption.APPEND else StandardOpenOption.TRUNCATE_EXISTING
            connection.inputStream.use { input ->
                Files.newOutputStream(partial, StandardOpenOption.CREATE, StandardOpenOption.WRITE, mode).use { output ->
                    val buffer = ByteArray(128 * 1024)
                    var nextProgress = bytes
                    publish(ModelDownloadProgress(model, bytes))
                    while (bytes < model.size) {
                        context.ensureActive()
                        val count = input.read(buffer, 0, minOf(buffer.size.toLong(), model.size - bytes).toInt())
                        if (count < 0) throw IOException("モデルの取得が途中で止まりました。もう一度選ぶと再開します。")
                        output.write(buffer, 0, count)
                        bytes += count
                        if (bytes >= nextProgress) {
                            publish(ModelDownloadProgress(model, bytes))
                            nextProgress = bytes + 1_048_576
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    companion object {
        private fun sizeOf(path: Path): Long = if (Files.isRegularFile(path)) Files.size(path) else 0L

        suspend fun verify(path: Path, model: DesktopModelCatalog): Boolean {
            if (sizeOf(path) != model.size) return false
            val context = currentCoroutineContext()
            val digest = MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(128 * 1024)
                while (true) {
                    context.ensureActive()
                    val count = input.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                }
            }
            return HexFormat.of().formatHex(digest.digest()) == model.sha256
        }
    }
}
