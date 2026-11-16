package app.plamotranslate.ocr

import android.graphics.Bitmap

/**
 * 認識器（docs/設計/画面翻訳.md「読む流れ」）。画像を読み、認識器ごとの1行ずつの読みを返す。
 * どの行を採るかは決めない（[ChoicePolicy]）。
 */
internal interface LineReader : AutoCloseable {
    /** 重い認識器か（1枚に1秒近くかかる）。白縁の読み比べの2回目には使わない。 */
    val heavy: Boolean

    /** この認識器が返せる行の認識器の名前。PP-OCR は1回の検出から複数の認識器の行を返す。 */
    val sources: Set<Source>

    /** [wanted] のうち自分が返せる認識器の行を読む。 */
    suspend fun read(bitmap: Bitmap, wanted: Set<Source>): LineReading
}

/** 認識器が読んだ行。[glyphs] は ML Kit 中国語の1文字ずつの位置（縦書きの列を組み直すのに使う）。 */
internal class LineReading(
    val lines: Map<Source, List<Candidate>>,
    val glyphs: List<Glyph> = emptyList(),
)
