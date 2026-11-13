package app.plamotranslate.pipeline.camera

/** 段落の原文の組み立て。 */
object CameraText {
    /** 行をつなぐ。韓国語・欧文は単語の間で折り返すので空白、日本語・中国語は詰める。 */
    fun join(parts: List<String>): String {
        val trimmed = parts.map { it.trim() }
        val spaced = trimmed.any { p -> p.any { it in '가'..'힣' || it in 'A'..'Z' || it in 'a'..'z' } }
        return trimmed.joinToString(if (spaced) " " else "")
    }
}
