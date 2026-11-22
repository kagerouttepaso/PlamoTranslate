package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.ModelFile
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.speech.SpeechModel
import java.nio.file.Files
import java.nio.file.Path

internal object DesktopSpeechModels {
    fun requiredFiles(model: SpeechModel): List<ModelFile> =
        listOf(SharedModelCatalog.sileroVad) + SharedModelCatalog.recognition(model)

    fun downloadFiles(model: SpeechModel): List<ModelFile> =
        if (model.identifiesLanguage) requiredFiles(model) else SharedModelCatalog.whisperTiny + requiredFiles(model)

    val files = SpeechModel.entries.flatMap(::downloadFiles).distinct()

    // 手動で配置した既存モデルも、読み取れるファイルであれば利用できる。
    fun isInstalled(directory: Path, model: ModelFile): Boolean {
        val path = directory.resolve(model.path)
        return Files.isRegularFile(path) && Files.isReadable(path)
    }
}
