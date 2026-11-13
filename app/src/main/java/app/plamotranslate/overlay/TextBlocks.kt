package app.plamotranslate.overlay

import android.graphics.Rect
import app.plamotranslate.ocr.OcrLine
import app.plamotranslate.ocr.Script
import app.plamotranslate.ocr.ScriptDetector
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.layout.ReadingOrder
import app.plamotranslate.pipeline.layout.Rooms
import app.plamotranslate.pipeline.layout.TextLine
import app.plamotranslate.pipeline.layout.UnitKind
import app.plamotranslate.pipeline.screen.Box

/**
 * 訳の単位1つ（まとめる工程の出力を、端末の座標に戻したもの）。[text] は行を読む順に改行でつないだもの。
 * [kind] と [room]（占めてよい範囲。隣の単位との境目まで）で、枠の広げ方が決まる（[SlotLayout]）。[room] が無ければ広げない。
 */
internal data class TextBlock(
    val text: String,
    val bounds: Rect,
    val script: Script,
    val vertical: Boolean = false,
    val kind: UnitKind = UnitKind.BUBBLE,
    val room: Rect? = null,
) {
    companion object {
        /** 1行だけの単位（カメラ翻訳は行を追ってから段落にまとめるので、まず1行ずつ扱う）。 */
        fun of(line: OcrLine): TextBlock = TextBlock(line.text, Rect(line.bounds), line.script, line.toTextLine().verticalLine)
    }
}

/**
 * 読んだ行を [grouping] でまとめ、読み順に並べた訳の単位にし、占めてよい範囲を付ける（まとめる工程。判断は中核の
 * `LineGrouper` と `Rooms`）。[width]・[height] は画面（撮影画像）の大きさ。
 * 文字種の判定は読む側（core-ocr）の [ScriptDetector] で行い、中核へは結果だけを渡す。
 */
internal fun groupLines(lines: List<OcrLine>, grouping: Grouping, width: Int, height: Int): List<TextBlock> {
    val units = ReadingOrder.sort(grouping.grouper.group(lines.map { it.toTextLine() }))
    val rooms = Rooms.assign(units.map { it.box }, Box(0, 0, width, height))
    return units.mapIndexed { i, u ->
        val text = u.members.joinToString("\n") { lines[it].text }
        TextBlock(text, u.box.toRect(), ScriptDetector.detect(text), u.vertical, u.kind, rooms[i].toRect())
    }
}

internal fun OcrLine.toTextLine() = textLine(text, bounds)

internal fun textLine(text: String, bounds: Rect) =
    TextLine(text, bounds.toBox(), ScriptDetector.hasKana(text), ScriptDetector.detect(text) == Script.LATIN)

/** 端末の長方形を、中核の長方形にする。 */
internal fun Rect.toBox() = Box(left, top, right, bottom)

/** 中核の長方形を、端末の長方形にする。 */
internal fun Box.toRect() = Rect(left, top, right, bottom)
