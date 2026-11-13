package app.plamotranslate.overlay

import android.graphics.Bitmap
import app.plamotranslate.pipeline.layout.Rooms

/**
 * 枠を決める工程（docs/設計/画面翻訳.md「訳文の重ね表示」）。訳の単位の種類（[TextBlock.kind]）と占めてよい範囲（[TextBlock.room]）
 * だけを見て、枠の位置と色を決める。広げるかどうかは種類の性質（`UnitKind.expandable`）で、範囲で切る判断は中核の [Rooms]。
 * どのまとめ方と組み合わせても、描く枠（余白を足した後も）は占めてよい範囲を出ない（[OverlayStyle.boxRect]）。
 */
internal object SlotLayout {
    fun slot(block: TextBlock, bitmap: Bitmap, fit: Boolean): OverlaySlot {
        val colors = ColorSampler.sample(bitmap, block.bounds)
        val room = block.room
        val grown = if (fit && room != null && block.kind.expandable) {
            BubbleFit.expand(bitmap, block.bounds, colors.background)
                ?.let { Rooms.clip(it.toBox(), room.toBox(), block.bounds.toBox()).toRect() }
                ?.takeIf { it != block.bounds }
        } else {
            null
        }
        return OverlaySlot(grown ?: block.bounds, block.vertical, colors, bubble = grown != null, room = room)
    }
}
