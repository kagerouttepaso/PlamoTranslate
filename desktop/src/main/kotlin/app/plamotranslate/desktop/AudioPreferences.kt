package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.sameAs
import app.plamotranslate.log.Logs
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

internal data class SubtitleBounds(val x: Float, val y: Float, val width: Float, val height: Float)

internal class AudioPreferences(directory: Path) {
    private val selectionFile = directory.resolve("audio.properties")
    private val subtitleFile = directory.resolve("subtitles.properties")

    fun loadSelection(): AudioSelection {
        val properties = read(selectionFile)
        val defaults = AudioSelection()
        val self = properties.language("self")?.takeIf { it in spokenLanguages } ?: defaults.self
        val partner = properties.language("partner")?.takeIf { it in spokenLanguages && !it.sameAs(self) }
            ?: defaults.partner.takeUnless { it.sameAs(self) }
            ?: spokenLanguages.first { !it.sameAs(self) }
        val from = properties.language("playbackFrom")?.takeIf { it in spokenLanguages } ?: defaults.playbackFrom
        val to = properties.language("playbackTo")?.takeUnless { it.sameAs(from) }
            ?: Language.entries.first { !it.sameAs(from) }
        val source = AudioSource.entries.find { it.name == properties.getProperty("source") } ?: defaults.source
        return AudioSelection(source, self, partner, from, to)
    }

    fun saveSelection(selection: AudioSelection) {
        save(selectionFile, Properties().apply {
            setProperty("source", selection.source.name)
            setProperty("self", selection.self.code)
            setProperty("partner", selection.partner.code)
            setProperty("playbackFrom", selection.playbackFrom.code)
            setProperty("playbackTo", selection.playbackTo.code)
        })
    }

    fun loadBounds(): SubtitleBounds? {
        val p = read(subtitleFile)
        val x = p.number("x") ?: return null
        val y = p.number("y") ?: return null
        val width = p.number("width")?.takeIf { it >= 280 } ?: return null
        val height = p.number("height")?.takeIf { it >= 110 } ?: return null
        return SubtitleBounds(x, y, width, height)
    }

    fun saveBounds(bounds: SubtitleBounds) {
        save(subtitleFile, Properties().apply {
            setProperty("x", bounds.x.toString())
            setProperty("y", bounds.y.toString())
            setProperty("width", bounds.width.toString())
            setProperty("height", bounds.height.toString())
        })
    }

    private fun Properties.language(key: String) = getProperty(key)?.let(Language::ofCode)

    private fun Properties.number(key: String) = getProperty(key)?.toFloatOrNull()?.takeIf(Float::isFinite)

    private fun read(path: Path): Properties = Properties().apply {
        try {
            if (Files.exists(path)) Files.newInputStream(path).use(::load)
        } catch (error: IOException) {
            Logs.w("音声翻訳の設定を読み込めませんでした", error)
        } catch (error: IllegalArgumentException) {
            clear()
            Logs.w("音声翻訳の設定の形式が正しくありません", error)
        }
    }

    private fun save(file: Path, values: Properties) {
        try {
            Files.createDirectories(file.parent)
            val temp = Files.createTempFile(file.parent, "audio-", ".tmp")
            try {
                Files.newOutputStream(temp).use { values.store(it, null) }
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
            } finally {
                Files.deleteIfExists(temp)
            }
        } catch (error: IOException) {
            Logs.w("音声翻訳の設定を保存できませんでした", error)
        }
    }
}
