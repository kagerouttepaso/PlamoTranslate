package app.plamotranslate.ocr

/**
 * PP-OCR のモデルのファイルの並び（`files/models/ocr/` からの相対パス）。置き場の正本はここで、app のダウンロードの一覧
 * （ModelCatalog）もここを参照する。検出はどの認識器にも漫画用のものを使う。
 */
object PaddleModels {
    const val DETECTOR = "manga/det.onnx"
    const val MANGA_REC = "manga/rec.onnx"
    const val MANGA_DICT = "manga/dict.txt"
    const val KOREAN_REC = "korean/rec.onnx"
    const val KOREAN_DICT = "korean/dict.json"

    internal class Files(val model: String, val dict: String)

    /** 読み手ごとの認識のモデルと文字の表。 */
    internal val recognizers: Map<Source, Files> = mapOf(
        Source.PADDLE_ZH_JA to Files(MANGA_REC, MANGA_DICT),
        Source.PADDLE_KO to Files(KOREAN_REC, KOREAN_DICT),
    )
}
