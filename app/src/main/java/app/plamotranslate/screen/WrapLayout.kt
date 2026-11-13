package app.plamotranslate.screen

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 子を左から横に並べ、幅が足りなければ次の行へ折り返す（浮遊ボタンのメニューの選択肢。[ScreenMenu]）。
 * 1行に横へ詰めるだけだと、幅が足りない選択肢の文字が縦に潰れた（2026-11-13、作者の指摘「カスタムが縦に並ぶのがダサい」）。
 */
internal class WrapLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec)
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(limit, MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (x > 0 && x + child.measuredWidth > limit) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(resolveSize(widest, widthMeasureSpec), resolveSize(y + rowHeight, heightMeasureSpec))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val limit = r - l
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (x > 0 && x + child.measuredWidth > limit) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            child.layout(x, y, x + child.measuredWidth, y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
