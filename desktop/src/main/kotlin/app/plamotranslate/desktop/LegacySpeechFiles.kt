package app.plamotranslate.desktop

import app.plamotranslate.pipeline.models.SharedModelCatalog
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path

internal data class LegacySpeechFile(val path: String, val size: Long, val sha256: String)

internal class LegacySpeechFiles(private val directory: Path) {
    private fun verified(file: LegacySpeechFile): Path? {
        val path = directory.resolve(file.path).toAbsolutePath().normalize()
        if (!Files.isRegularFile(path, NOFOLLOW_LINKS) || path.toRealPath() != path) return null
        return path.takeIf { DesktopModelDownloads.verifyBytes(it, file.size, file.sha256) {} }
    }

    fun inspect(): List<LegacySpeechFile> = knownFiles.filter { verified(it) != null }

    fun remove(confirmed: List<LegacySpeechFile>) {
        require(confirmed.all { it in knownFiles })
        check(SharedModelCatalog.qwenGguf.all { DesktopModelDownloads.verifyBytes(directory.resolve(it.path), it.size, it.sha256) {} }) { "新しいモデルを先に準備してください。" }
        confirmed.forEach { file -> verified(file)?.let(Files::delete) }
    }

    private companion object {
        // 利用者が削除を選んだときに、かつて配布したファイルだけを照合する。
        val knownFiles = listOf(
            LegacySpeechFile("asr/qwen3-asr/conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
            LegacySpeechFile("asr/qwen3-asr/encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
            LegacySpeechFile("asr/qwen3-asr/decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
            LegacySpeechFile("asr/qwen3-asr/tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
            LegacySpeechFile("asr/qwen3-asr/tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
            LegacySpeechFile("asr/qwen3-asr/tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
        )
    }
}
