package app.plamotranslate.desktop

import app.plamotranslate.pipeline.translation.CacheScope
import app.plamotranslate.pipeline.translation.CachedTranslation
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.PersistentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.NoSuchFileException
import java.nio.file.StandardOpenOption.CREATE
import java.nio.file.StandardOpenOption.WRITE
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal interface ClearableTranslationCache {
    suspend fun count(): Long
    suspend fun clear()
}

internal class DesktopPdfCache(private val directory: Path) : PersistentCache, ClearableTranslationCache {
    // 開発版と配布版が同じキャッシュを開いていても、消去前の翻訳を後で書き戻さない。
    private val epoch = directory.resolveSibling("${directory.fileName}.generation")
    private val lockFile = directory.resolveSibling("${directory.fileName}.lock")
    override val generation: Int get() = try {
        Files.readString(epoch).trim().toInt()
    } catch (_: NoSuchFileException) {
        0
    }

    private companion object { val processLock = Mutex() }

    private suspend fun <T> locked(operation: () -> T): T = processLock.withLock {
        Files.createDirectories(lockFile.parent)
        FileChannel.open(lockFile, CREATE, WRITE).use { channel -> channel.lock().use { operation() } }
    }

    override suspend fun lookup(scope: CacheScope, minQuality: Int, texts: Collection<String>): Map<String, CachedTranslation> =
        withContext(Dispatchers.IO) {
            locked {
                buildMap {
                    for (text in texts) read(path(scope, text))?.takeIf { it.quality >= minQuality }?.let { put(text, it) }
                }
            }
        }

    override suspend fun store(scope: CacheScope, origin: ModelOrigin, generation: Int, translations: Map<String, String>) {
        withContext(Dispatchers.IO) {
            locked {
                if (generation != this@DesktopPdfCache.generation) return@locked
                for ((source, translation) in translations) {
                    val target = path(scope, source)
                    if ((read(target)?.quality ?: Int.MIN_VALUE) > origin.quality) continue
                    Files.createDirectories(target.parent)
                    val temporary = Files.createTempFile(target.parent, "pending-", ".tmp")
                    try {
                        DataOutputStream(Files.newOutputStream(temporary)).use { output ->
                            output.writeInt(origin.quality)
                            output.write(translation.toByteArray(Charsets.UTF_8))
                        }
                        Files.move(temporary, target, ATOMIC_MOVE, REPLACE_EXISTING)
                    } finally {
                        Files.deleteIfExists(temporary)
                    }
                }
            }
        }
    }

    override suspend fun count(): Long = withContext(Dispatchers.IO) {
        locked {
            if (!Files.isDirectory(directory)) 0L else Files.walk(directory, 2).use { paths ->
                paths.filter { it.fileName.toString().length == 62 && Files.isRegularFile(it) }.count()
            }
        }
    }

    override suspend fun clear() {
        withContext(Dispatchers.IO) {
            locked {
                val next = Files.createTempFile(epoch.parent, "cache-generation-", ".tmp")
                try {
                    Files.writeString(next, (generation + 1).toString())
                    Files.move(next, epoch, ATOMIC_MOVE, REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(next)
                }
                if (Files.exists(directory)) {
                    Files.walk(directory).use { paths ->
                        paths.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)
                    }
                }
            }
        }
    }

    private fun path(scope: CacheScope, text: String): Path {
        val key = scope.id
        val hash = MessageDigest.getInstance("SHA-256").digest("${key.length}:$key$text".toByteArray(Charsets.UTF_8))
        val name = HexFormat.of().formatHex(hash)
        return directory.resolve(name.take(2)).resolve(name.drop(2))
    }

    private fun read(path: Path): CachedTranslation? = try {
        DataInputStream(Files.newInputStream(path)).use { input ->
            val quality = input.readInt()
            CachedTranslation(input.readBytes().toString(Charsets.UTF_8), quality)
        }
    } catch (_: IOException) {
        null
    }
}
