package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.ModelFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.FileTime

internal object VerifiedSpeechFiles {
    private data class Stamp(val size: Long, val modified: FileTime, val fileKey: Any?, val digest: String)
    private val verified = mutableMapOf<Path, Stamp>()

    @Synchronized fun matches(path: Path, model: ModelFile): Boolean {
        if (!Files.isRegularFile(path)) return false
        val attributes = Files.readAttributes(path, BasicFileAttributes::class.java)
        val stamp = Stamp(attributes.size(), attributes.lastModifiedTime(), attributes.fileKey(), model.sha256)
        if (stamp.size != model.size) return false
        if (verified[path] == stamp) return true
        return DesktopModelDownloads.verifyBytes(path, model.size, model.sha256) {}.also { if (it) verified[path] = stamp }
    }
}
