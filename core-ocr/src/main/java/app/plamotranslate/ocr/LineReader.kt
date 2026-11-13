package app.plamotranslate.ocr

import android.graphics.Bitmap

/**
 * 読み手（docs/設計/画面翻訳.md「読む流れ」）。画像を読み、読み手ごとの1行ずつの読みを返す。
 * どの行を採るかは決めない（[ChoicePolicy]）。
 */
internal interface LineReader : AutoCloseable {
    /** 重い読み手か（1枚に1秒近くかかる）。白縁の読み比べの2回目には使わない。 */
    val heavy: Boolean

    /** この読み手が返せる行の読み手の名前。PP-OCR は1回の検出から複数の認識器の行を返す。 */
    val sources: Set<Source>

    /** [wanted] のうち自分が返せる読み手の行を読む。 */
    suspend fun read(bitmap: Bitmap, wanted: Set<Source>): LineReading
}

/** 読み手が読んだ行。[glyphs] は ML Kit 中国語の1文字ずつの位置（縦書きの列を組み直すのに使う）。 */
internal class LineReading(
    val lines: Map<Source, List<Candidate>>,
    val glyphs: List<Glyph> = emptyList(),
)
