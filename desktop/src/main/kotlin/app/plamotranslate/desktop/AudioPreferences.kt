package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.speech.SpeechModel
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
        val recognition = defaults.recognition
        return AudioSelection(
            source = AudioSource.entries.find { it.name == properties.getProperty("source") } ?: defaults.source,
            self = properties.language("self") ?: defaults.self,
            partner = properties.language("partner"),
            playbackFrom = properties.language("playbackFrom") ?: defaults.playbackFrom,
            playbackTo = properties.language("playbackTo") ?: defaults.playbackTo,
            recognition = recognition.copy(
                model = SpeechModel.entries.find { it.name == properties.getProperty("recognitionModel") } ?: recognition.model,
                transcribeThreads = properties.getProperty("transcribeThreads")?.toIntOrNull()?.takeIf { it in 0..16 } ?: recognition.transcribeThreads,
                identifyThreads = properties.getProperty("identifyThreads")?.toIntOrNull()?.takeIf { it in 1..8 } ?: recognition.identifyThreads,
                cardLanguageSeconds = properties.number("cardLanguageSeconds")?.takeIf { it in 0.3f..3f } ?: recognition.cardLanguageSeconds,
                vadThreshold = properties.number("vadThreshold")?.takeIf { it in 0.1f..0.9f } ?: recognition.vadThreshold,
                vadMinSpeechSeconds = properties.number("vadMinSpeechSeconds")?.takeIf { it in 0.05f..1f } ?: recognition.vadMinSpeechSeconds,
            ),
        ).validLanguages()
    }

    fun saveSelection(selection: AudioSelection) {
        save(selectionFile, Properties().apply {
            setProperty("source", selection.source.name)
            setProperty("self", selection.self.code)
            selection.partner?.let { setProperty("partner", it.code) }
            setProperty("playbackFrom", selection.playbackFrom.code)
            setProperty("playbackTo", selection.playbackTo.code)
            setProperty("recognitionModel", selection.recognition.model.name)
            setProperty("transcribeThreads", selection.recognition.transcribeThreads.toString())
            setProperty("identifyThreads", selection.recognition.identifyThreads.toString())
            setProperty("cardLanguageSeconds", selection.recognition.cardLanguageSeconds.toString())
            setProperty("vadThreshold", selection.recognition.vadThreshold.toString())
            setProperty("vadMinSpeechSeconds", selection.recognition.vadMinSpeechSeconds.toString())
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
