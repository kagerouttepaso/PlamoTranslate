package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.ModelFile
import app.plamotranslate.pipeline.models.SharedModelCatalog
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal class LegacySpeechFiles(private val directory: Path) {
    private fun verified(file: ModelFile): Path? {
        val path = directory.resolve(file.path).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path) return null
        return path.takeIf { DesktopModelDownloads.verifyBytes(it, file) {} }
    }

    fun inspect(): List<ModelFile> = SharedModelCatalog.qwen3Asr.filter { verified(it) != null }

    fun remove(confirmed: List<ModelFile>) {
        require(confirmed.all { it in SharedModelCatalog.qwen3Asr })
        check(SharedModelCatalog.qwenGguf.all { DesktopModelDownloads.verifyBytes(directory.resolve(it.path), it) {} }) { "新しいモデルを先に準備してください。" }
        confirmed.forEach { file -> verified(file)?.let(Files::delete) }
    }
}
