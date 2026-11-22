package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.TranslationModel
import java.nio.file.Path

internal enum class DesktopModelCatalog(private val model: TranslationModel) {
    SMALL(TranslationModel.SMALL),
    LARGE(TranslationModel.LARGE),
    ;

    val title get() = "PLaMo 3 Translate ${model.label}"
    val filename get() = model.file.path.substringAfterLast('/')
    val url get() = model.file.url
    val size get() = model.file.size
    val sha256 get() = model.file.sha256
    val origin get() = model.origin

    fun path(directory: Path): Path = directory.resolve(filename)
}
