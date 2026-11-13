package app.plamotranslate.overlay

import android.graphics.Rect
import app.plamotranslate.ocr.OcrLine
import app.plamotranslate.ocr.Script
import app.plamotranslate.ocr.ScriptDetector
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.layout.ReadingOrder
import app.plamotranslate.pipeline.layout.TextLine
import app.plamotranslate.pipeline.layout.UnitKind
import app.plamotranslate.pipeline.screen.Box

/**
 * 訳の単位1つ（まとめる工程の出力を、端末の座標に戻したもの）。[text] は行を読む順に改行でつないだもの。
 * [kind] で枠の広げ方が決まる（docs/設計/画面翻訳.md「訳文の重ね表示」）。
 */
internal data class TextBlock(
    val text: String,
    val bounds: Rect,
    val script: Script,
    val vertical: Boolean = false,
    val kind: UnitKind = UnitKind.BUBBLE,
) {
    companion object {
        /** 1行だけの単位（カメラ翻訳は行を追ってから段落にまとめるので、まず1行ずつ扱う）。 */
        fun of(line: OcrLine): TextBlock = TextBlock(line.text, Rect(line.bounds), line.script, line.toTextLine().verticalLine)
    }
}

/**
 * 読んだ行を [grouping] でまとめ、読み順に並べた訳の単位にする（まとめる工程。判断は中核の `LineGrouper`）。
 * 文字種の判定は読む側（core-ocr）の [ScriptDetector] で行い、中核へは結果だけを渡す。
 */
internal fun groupLines(lines: List<OcrLine>, grouping: Grouping): List<TextBlock> {
    val units = ReadingOrder.sort(grouping.grouper.group(lines.map { it.toTextLine() }))
    return units.map { u ->
        val text = u.members.joinToString("\n") { lines[it].text }
        TextBlock(text, u.box.toRect(), ScriptDetector.detect(text), u.vertical, u.kind)
    }
}

internal fun OcrLine.toTextLine() = textLine(text, bounds)

internal fun textLine(text: String, bounds: Rect) =
    TextLine(text, bounds.toBox(), ScriptDetector.hasKana(text), ScriptDetector.detect(text) == Script.LATIN)

/** 端末の長方形を、中核の長方形にする。 */
internal fun Rect.toBox() = Box(left, top, right, bottom)

/** 中核の長方形を、端末の長方形にする。 */
internal fun Box.toRect() = Rect(left, top, right, bottom)
