package app.plamotranslate.desktop

import app.plamotranslate.pipeline.translation.ModelOrigin
import java.nio.file.Path

internal enum class DesktopModelCatalog(
    val title: String,
    val url: String,
    val filename: String,
    val size: Long,
    val sha256: String,
    val quality: Int,
) {
    SMALL(
        "PLaMo 3 Translate 0.8B",
        "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/e10c327b7a7ce84ffc427cb4b09360b781429fda/plamo-3-translate-0.8b-Q4_K_M.gguf",
        "plamo-3-translate-0.8b-Q4_K_M.gguf",
        573_126_848,
        "70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6",
        0,
    ),
    LARGE(
        "PLaMo 3 Translate 2B",
        "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/d44589ecd5287cd64452e9cd1e2cfdad904fe216/plamo-3-translate-2b-Q4_K_M.gguf",
        "plamo-3-translate-2b-Q4_K_M.gguf",
        1_807_202_144,
        "2189a8a0b4b359f59443ec36cfb9c9c3f603a678c00dbcee8598b3f7ea7c12fb",
        1,
    );

    val origin get() = ModelOrigin(sha256.take(16), quality)

    fun path(directory: Path): Path = directory.resolve(filename)
}
