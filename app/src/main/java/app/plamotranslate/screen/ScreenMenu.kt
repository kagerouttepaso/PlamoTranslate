package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plamotranslate.overlay.dp

/** 浮遊ボタンのメニューの1行。[Choice] は設定1つ（横に並べた選択肢から1つ選ぶ）、[Action] は押すと閉じて行う操作。 */
internal sealed interface MenuEntry {
    class Choice(val label: String, val options: List<Option>) : MenuEntry
    class Action(val label: String, val action: () -> Unit) : MenuEntry
}

/**
 * [MenuEntry.Choice] の選択肢。[enabled] でないものは薄く出し、押すと [disabledHint] をトーストで出す。
 */
internal class Option(
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val disabledHint: String? = null,
    val action: () -> Unit,
)

/**
 * 浮遊ボタンの長押しで出すメニュー（2026-11-05、作者の要望）。
 * 設定1つを1行にし、選択肢を横に並べる（2026-11-13、作者の指摘「縦長で使いにくい」。それまでは見出しと選択肢を1行ずつ縦に並べ、約20行あった）。
 * 選択肢を押すと設定を変え、メニューを開いたまま選んだ印を描き直す（いくつかの設定を続けて変えられる）。
 * メニューの外に触れたら閉じる。画面に収まらないときはスクロールする。
 */
internal class ScreenMenu(private val context: Context, private val windowManager: WindowManager) {
    private var root: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchor = 0 to 0
    private var build: () -> List<MenuEntry> = { emptyList() }

    /** メニューを閉じたとき（外に触れた・操作の行を選んだ）。操作の行を選んだときは、行の処理より先に呼ぶ。 */
    var onDismiss: (() -> Unit)? = null

    /** [anchorX]・[anchorY]（浮遊ボタンの位置）の左にメニューを出す。行は [entries] で作り、選択肢を押すたびに作り直す。 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, entries: () -> List<MenuEntry>) {
        // 前のメニューが残っていれば外す（開き直しなので、閉じたことは知らせない）
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        anchor = anchorX to anchorY
        build = entries
        val view = ScrollView(context).apply {
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss() else OwnTouch.mark()
                false
            }
        }
        val p = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.START }
        fill(view, p)
        windowManager.addView(view, p)
        root = view
        params = p
    }

    /** 選択肢を押した後、選んだ印を今の設定に合わせて描き直す。 */
    private fun refresh() {
        val view = root ?: return
        val p = params ?: return
        fill(view, p)
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    /** 行を作って [view] に入れ、大きさと位置を [p] に書く。 */
    private fun fill(view: ScrollView, p: WindowManager.LayoutParams) {
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 32, 32, 40))
                cornerRadius = context.dp(12).toFloat()
            }
        }
        for (entry in build()) list.addView(
            when (entry) {
                is MenuEntry.Choice -> choiceRow(entry)
                is MenuEntry.Action -> actionRow(entry)
            },
        )
        view.removeAllViews()
        view.addView(list)

        val screen = windowManager.maximumWindowMetrics.bounds
        list.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width() - context.dp(16), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val height = minOf(list.measuredHeight, screen.height() - context.dp(32))
        val (anchorX, anchorY) = anchor
        p.height = height
        // ボタンの左に出す（画面の右端に寄せたボタンでも見切れないように）。下にはみ出すなら上へずらす
        p.x = (anchorX - list.measuredWidth - context.dp(8)).coerceAtLeast(context.dp(8))
        p.y = anchorY.coerceAtMost(screen.height() - height - context.dp(16)).coerceAtLeast(context.dp(16))
    }

    /** 左に設定の名前、右に選択肢を横に並べた1行。 */
    private fun choiceRow(entry: MenuEntry.Choice): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, context.dp(4), 0, context.dp(4))
        addView(TextView(context).apply {
            text = entry.label
            setTextColor(Color.argb(170, 255, 255, 255))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = context.dp(68)
            setPadding(context.dp(2), 0, context.dp(6), 0)
        })
        entry.options.forEach { addView(chip(it)) }
    }

    /** 選択肢1つ。選んでいるものは塗り、ほかは枠だけにする。 */
    private fun chip(option: Option): View = TextView(context).apply {
        text = option.label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(Color.WHITE)
        gravity = Gravity.CENTER
        setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(16).toFloat()
            if (option.checked) setColor(Color.rgb(98, 0, 238)) else setStroke(context.dp(1), Color.argb(110, 255, 255, 255))
        }
        if (!option.enabled) alpha = 0.4f
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { marginEnd = context.dp(6) }
        setOnClickListener {
            OwnTouch.mark()
            if (!option.enabled) {
                option.disabledHint?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
                return@setOnClickListener
            }
            if (option.checked) return@setOnClickListener
            option.action()
            refresh()
        }
    }

    /** 押すとメニューを閉じて行う操作の行（終了）。 */
    private fun actionRow(entry: MenuEntry.Action): View = TextView(context).apply {
        text = entry.label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.END
        setPadding(context.dp(12), context.dp(10), context.dp(4), context.dp(4))
        setOnClickListener {
            OwnTouch.mark()
            dismiss()
            entry.action()
        }
    }

    fun dismiss() {
        val view = root ?: return
        runCatching { windowManager.removeView(view) }
        root = null
        params = null
        onDismiss?.invoke()
    }

    /** メニューを出しているか（自動で訳すとき、選んでいる途中にメニューを閉じないよう、その間は訳さない）。 */
    val isShowing: Boolean get() = root != null
}
