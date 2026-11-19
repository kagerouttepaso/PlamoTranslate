package app.plamotranslate.screen

import android.content.Context
import android.view.View
import android.view.ViewGroup

/**
 * 子を左から横に並べ、幅が足りなければ次の行へ折り返す（フローティングボタンのメニューの選択肢。[ScreenMenu]）。
 * 1行に横へ詰めるだけだと、幅が足りない選択肢の文字が縦に潰れた（2026-11-13、作者の指摘「カスタムが縦に並ぶのがダサい」）。
 * 余白（padding）は測る大きさに含め、子は余白の内側に置く。含めないと、余白の分だけ最後の行が切り取られる
 * （ViewGroup は既定で余白の外側を描かない。2026-11-19、「他の言語」のページで最後の行が欠けた）。
 */
internal class WrapLayout(context: Context, private val gap: Int) : ViewGroup(context) {
    /** 必ず新しい行の頭に置く子（[addViewOnNewLine]）。 */
    private val lineHeads = HashSet<View>()

    /** [child] を、幅が足りていても新しい行の頭に置く（画面の幅や前の子の長さで、並び方が変わらないようにする）。 */
    fun addViewOnNewLine(child: View) {
        lineHeads += child
        addView(child)
    }

    override fun onViewRemoved(child: View) {
        super.onViewRemoved(child)
        lineHeads -= child
    }

    /** [child] の前で折り返すか。[x] はその行で置いた幅。 */
    private fun wrapsBefore(child: View, x: Int, limit: Int): Boolean = x > 0 && (child in lineHeads || x + child.measuredWidth > limit)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val limit = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) Int.MAX_VALUE
        else MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        var widest = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            child.measure(MeasureSpec.makeMeasureSpec(limit.coerceAtLeast(0), MeasureSpec.AT_MOST), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            if (wrapsBefore(child, x, limit)) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            x += child.measuredWidth + gap
            widest = maxOf(widest, x - gap)
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
        setMeasuredDimension(
            resolveSize(widest + paddingLeft + paddingRight, widthMeasureSpec),
            resolveSize(y + rowHeight + paddingTop + paddingBottom, heightMeasureSpec),
        )
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val limit = r - l - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var rowHeight = 0
        for (i in 0 until childCount) {
            val child = getChildAt(i)
            if (child.visibility == View.GONE) continue
            if (wrapsBefore(child, x, limit)) {
                y += rowHeight + gap
                x = 0
                rowHeight = 0
            }
            child.layout(paddingLeft + x, paddingTop + y, paddingLeft + x + child.measuredWidth, paddingTop + y + child.measuredHeight)
            x += child.measuredWidth + gap
            rowHeight = maxOf(rowHeight, child.measuredHeight)
        }
    }
}
