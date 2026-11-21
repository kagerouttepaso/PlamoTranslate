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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.security.MessageDigest
import java.util.HexFormat

internal class DesktopPdfCache(private val directory: Path) : PersistentCache {
    override val generation = 0
    private val lock = Mutex()

    override suspend fun lookup(scope: CacheScope, minQuality: Int, texts: Collection<String>): Map<String, CachedTranslation> =
        withContext(Dispatchers.IO) {
            lock.withLock {
                buildMap {
                    for (text in texts) read(path(scope, text))?.takeIf { it.quality >= minQuality }?.let { put(text, it) }
                }
            }
        }

    override suspend fun store(scope: CacheScope, origin: ModelOrigin, generation: Int, translations: Map<String, String>) {
        if (generation != this.generation) return
        withContext(Dispatchers.IO) {
            lock.withLock {
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
