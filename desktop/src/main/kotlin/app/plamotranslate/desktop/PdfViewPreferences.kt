package app.plamotranslate.desktop

import app.plamotranslate.desktop.pdf.PdfSpread
import app.plamotranslate.desktop.pdf.PdfZoom
import app.plamotranslate.log.Logs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** PDF のビューアの見え方（拡大率・並べ方・見出しの欄）を保存する。 */
internal class PdfViewPreferences(directory: Path) {
    private val file = directory.resolve("pdf-view.properties")

    fun load(): PdfViewSettings {
        val properties = Properties()
        try {
            if (Files.exists(file)) Files.newInputStream(file).use(properties::load)
        } catch (error: IOException) {
            Logs.w("PDF の表示の設定を読み込めませんでした", error)
        } catch (error: IllegalArgumentException) {
            Logs.w("PDF の表示の設定の形式が正しくありません", error)
        }
        val defaults = PdfViewSettings()
        return PdfViewSettings(
            zoom = PdfZoom.parse(properties.getProperty("zoom")) ?: defaults.zoom,
            spread = PdfSpread.entries.find { it.name == properties.getProperty("spread") } ?: defaults.spread,
            outline = properties.getProperty("outline")?.toBooleanStrictOrNull() ?: defaults.outline,
        )
    }

    fun save(settings: PdfViewSettings) {
        val values = Properties().apply {
            setProperty("zoom", PdfZoom.key(settings.zoom))
            setProperty("spread", settings.spread.name)
            setProperty("outline", settings.outline.toString())
        }
        try {
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, "pdf-view-", ".tmp")
            try {
                Files.newOutputStream(temp).use { values.store(it, null) }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (error: IOException) {
            Logs.w("PDF の表示の設定を保存できませんでした", error)
        }
    }
}
