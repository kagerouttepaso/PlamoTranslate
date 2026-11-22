package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.TranslationModel
import java.nio.file.Path

internal enum class DesktopModelCatalog(private val model: TranslationModel) {
    SMALL(TranslationModel.SMALL),
    LARGE(TranslationModel.LARGE),
    ;

    val title get() = "PLaMo 3 Translate ${model.label}"
    val file = model.file.copy(path = model.file.path.substringAfterLast('/'))
    val filename get() = file.path
    val size get() = file.size
    val origin get() = model.origin

    fun path(directory: Path): Path = directory.resolve(filename)
}
