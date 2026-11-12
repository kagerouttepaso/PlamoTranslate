package app.plamotranslate.screen

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.view.View
import app.plamotranslate.overlay.spToPx

/**
 * 横書きの訳を枠に収めて描く。重ね表示（[HorizontalTextView]）と評価の描画（BenchActivity）で同じ計算を使う。
 *
 * 2026-11-12に、TextView の自動の文字の大きさをやめてこれに替えた（作者の指摘「2行分の高さがないのに2行になって見切れる」
 * 「長文を訳している間、大きい文字で見切れたままがしばらく続く」）。TextView の自動の大きさは、大きさの決まった窓では
 * append() で文字が増えても計算し直さないため、流し込みの最初の短い文で決めた大きな文字のまま、増えた分が枠からはみ出していた。
 */
object Yokogaki {
    /**
     * 幅 [width] で折り返した [text] が高さ [height] に収まる、最大の文字の大きさ（sp単位の整数）で組む。
     * 収まらなければ最小の大きさで組み、2つ目の値を false にする。[spPx] は1spの画素数。
     */
    fun fit(
        text: CharSequence, paint: TextPaint, width: Int, height: Int,
        minSp: Int, maxSp: Int, spPx: Float, center: Boolean,
    ): Pair<StaticLayout, Boolean> {
        fun build(sp: Int): StaticLayout {
            paint.textSize = sp * spPx
            return StaticLayout.Builder.obtain(text, 0, text.length, paint, width.coerceAtLeast(1))
                .setAlignment(if (center) Layout.Alignment.ALIGN_CENTER else Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .build()
        }
        // 収まる最大の大きさを二分探索で探す（流し込みの1文字ごとに組み直すので、上から1つずつ試すより速くする）
        var lo = minSp
        var hi = maxSp
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (build(mid).height <= height) lo = mid else hi = mid - 1
        }
        val layout = build(lo)
        return layout to (layout.height <= height)
    }

    /** [layout] を [box] の中に、上下の真ん中にそろえて描く。はみ出した分は枠で切る。 */
    fun draw(canvas: Canvas, layout: StaticLayout, box: RectF) {
        canvas.save()
        canvas.clipRect(box)
        canvas.translate(box.left, box.top + ((box.height() - layout.height) / 2f).coerceAtLeast(0f))
        layout.draw(canvas)
        canvas.restore()
    }
}

/**
 * 重ね表示で使う横書きの訳の枠。文字が変わるたびと枠の大きさが変わるたびに、[OverlayStyle] の範囲で
 * 収まる最大の大きさを選び直す（流し込みの途中でも、増えた分に合わせて縮む）。
 * [center] の枠（吹き出しまで広げた枠）は行を真ん中にそろえ、ほかは左にそろえる。上下はどちらも真ん中。
 */
class HorizontalTextView(context: Context, private val center: Boolean) : View(context) {
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = android.graphics.Color.WHITE }
    private val spPx = context.spToPx(1f)
    private val box = RectF()
    private var layout: StaticLayout? = null

    var textColor: Int
        get() = paint.color
        set(value) {
            paint.color = value
            invalidate()
        }

    /** 縁取りの色（枠の背景色。[OverlayStyle.applyHalo]）。 */
    fun setHalo(background: Int) {
        OverlayStyle.applyHalo(paint, background, resources.displayMetrics.density)
        layout = null
        invalidate()
    }

    var text: String = ""
        set(value) {
            field = value
            layout = null
            invalidate()
        }

    fun append(delta: String) {
        text += delta
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layout = null
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val pad = OverlayStyle.H_PADDING_DP * resources.displayMetrics.density
        box.set(pad, 0f, width - pad, height.toFloat())
        val current = layout ?: Yokogaki.fit(
            text, paint, box.width().toInt(), box.height().toInt(),
            OverlayStyle.MIN_SP, OverlayStyle.MAX_SP, spPx, center,
        ).first.also { layout = it }
        Yokogaki.draw(canvas, current, box)
    }
}
