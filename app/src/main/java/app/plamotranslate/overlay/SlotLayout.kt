package app.plamotranslate.overlay

import android.graphics.Bitmap
import app.plamotranslate.pipeline.layout.Rooms
import app.plamotranslate.pipeline.layout.UnitKind

/**
 * 枠を決める工程（docs/設計/画面翻訳.md「訳文の重ね表示」）。訳の単位の種類（[TextBlock.kind]）と占めてよい範囲（[TextBlock.room]）
 * だけを見て、枠の位置と色を決める。どのまとめ方と組み合わせても、枠は占めてよい範囲を出ない（隣の単位へはみ出さない）。
 *
 * | 種類 | 「広げる」を選んだとき |
 * | --- | --- |
 * | 吹き出し・メニューの項目 | 占めてよい範囲の中で、背景色の閉じた範囲まで（[BubbleFit]） |
 * | 段落 | 広げない |
 */
internal object SlotLayout {
    fun slot(block: TextBlock, bitmap: Bitmap, fit: Boolean): OverlaySlot {
        val colors = ColorSampler.sample(bitmap, block.bounds)
        val room = block.room
        val grown = if (fit && room != null && expands(block.kind)) {
            BubbleFit.expand(bitmap, block.bounds, colors.background)
                ?.let { Rooms.clip(it.toBox(), room.toBox(), block.bounds.toBox()).toRect() }
                ?.takeIf { it != block.bounds }
        } else {
            null
        }
        return OverlaySlot(grown ?: block.bounds, block.vertical, colors, bubble = grown != null)
    }

    private fun expands(kind: UnitKind): Boolean = when (kind) {
        UnitKind.BUBBLE, UnitKind.MENU_ITEM -> true
        UnitKind.PARAGRAPH -> false
    }
}
