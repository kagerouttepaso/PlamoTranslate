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
import app.plamotranslate.overlay.OverlayPalette
import app.plamotranslate.overlay.dp

/**
 * フローティングボタンのメニューの1行。[Choice] は設定1つ（横に並べた選択肢から1つ選ぶ）、[Action] は押すと閉じて行う操作。
 * [Choice.more] があれば、選択肢の最後に、ほかの選択肢を並べたページを開く選択肢を出す。
 */
internal sealed interface MenuEntry {
    class Choice(val label: String, val options: List<Option>, val more: OptionPage? = null) : MenuEntry
    class Action(val label: String, val action: () -> Unit) : MenuEntry
}

/** [MenuEntry.Choice] の選択肢。[enabled] でないものは薄く出し、押すと [disabledHint] をトーストで出す。 */
internal class Option(
    val label: String,
    val checked: Boolean,
    val enabled: Boolean = true,
    val disabledHint: String? = null,
    val action: () -> Unit,
)

/**
 * 1行に並べきれない選択肢を並べるページ（2026-11-19、原文の「他の言語」）。行に並べるとメニューが縦に長くなるので、ページに分ける。
 * 行には [label] の選択肢として出し（ページの選択肢を選んでいれば [checked]）、押すとメニューの中身がこのページに替わる。
 * ページはメニューと同じ幅に選択肢を折り返して並べ、選ぶか上の戻る行を押すと元のメニューに戻る。
 */
internal class OptionPage(val label: String, val checked: Boolean, val title: String, val options: () -> List<Option>)

/**
 * フローティングボタンの長押しで出すメニュー（2026-11-05、作者の要望）。
 * 設定1つを1行にし、選択肢を横に並べる（2026-11-13、作者の指摘「縦長で使いにくい」。それまでは見出しと選択肢を1行ずつ縦に並べ、約20行あった）。
 * 選択肢を押すと設定を変え、メニューを開いたままチェックマークを描き直す（いくつかの設定を続けて変えられる）。
 * メニューの外に触れたら閉じる。画面に収まらないときはスクロールする。
 */
internal class ScreenMenu(private val context: Context, private val windowManager: WindowManager) {
    private var root: ScrollView? = null
    private var params: WindowManager.LayoutParams? = null
    private var anchor = 0 to 0
    private var build: () -> List<MenuEntry> = { emptyList() }

    /** 開いているページ（null なら設定の行の一覧）。 */
    private var page: OptionPage? = null

    /** 設定の行の一覧を最後に出したときの幅。ページを同じ幅で出し、開いたときにメニューの幅と位置が変わらないようにする。 */
    private var rootWidth = 0

    /** 今のテーマの色（開くたび・描き直すたびに読み直す。docs/設計/デザイン言語.md「色」）。 */
    private var palette = OverlayPalette.current(context)

    /** メニューを閉じたとき（外に触れた・操作の行を選んだ）。操作の行を選んだときは、行の処理より先に呼ぶ。 */
    var onDismiss: (() -> Unit)? = null

    /** [anchorX]・[anchorY]（フローティングボタンの位置）の左にメニューを出す。行は [entries] で作り、選択肢を押すたびに作り直す。 */
    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, entries: () -> List<MenuEntry>) {
        // 前のメニューが残っていれば外す（開き直しなので、閉じたことは知らせない）
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        anchor = anchorX to anchorY
        build = entries
        page = null
        val view = ScrollView(context).apply {
            // 画面に収まらないとき（横向きなど）は、続きがあると分かるようにスクロールバーを出したままにする。
            // 収まっていればスクロールできないので、バーは出ない
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
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

    /** チェックマークと選べるかを、今の設定に合わせて描き直す（選択肢を押した後と、翻訳モデルの状態が変わったとき）。 */
    fun refresh() {
        val view = root ?: return
        val p = params ?: return
        fill(view, p)
        runCatching { windowManager.updateViewLayout(view, p) }
    }

    /** 行を作って [view] に入れ、大きさと位置を [p] に書く。 */
    private fun fill(view: ScrollView, p: WindowManager.LayoutParams) {
        palette = OverlayPalette.current(context)
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(10), context.dp(8), context.dp(10), context.dp(8))
            background = GradientDrawable().apply {
                setColor(palette.surface)
                setStroke(context.dp(1), palette.outline)
                cornerRadius = context.dp(12).toFloat()
            }
        }
        val open = page
        if (open == null) {
            for (entry in build()) list.addView(
                when (entry) {
                    is MenuEntry.Choice -> choiceRow(entry)
                    is MenuEntry.Action -> actionRow(entry)
                },
            )
        } else {
            pageRows(open).forEach(list::addView)
        }
        view.removeAllViews()
        view.addView(list)

        val screen = windowManager.maximumWindowMetrics.bounds
        val (anchorX, anchorY) = anchor
        // ボタンの左に収まる幅にする（ボタンに重ねない）。左が狭すぎるときだけ画面の幅まで使う
        val maxWidth = (anchorX - context.dp(16)).takeIf { it >= context.dp(280) } ?: (screen.width() - context.dp(16))
        // ページは設定の行の一覧と同じ幅にする（ページを開いてもメニューの幅と位置が変わらない）
        val widthSpec = if (open != null && rootWidth > 0) View.MeasureSpec.makeMeasureSpec(minOf(rootWidth, maxWidth), View.MeasureSpec.EXACTLY)
        else View.MeasureSpec.makeMeasureSpec(maxWidth, View.MeasureSpec.AT_MOST)
        list.measure(widthSpec, View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        if (open == null) rootWidth = list.measuredWidth
        // ウィンドウの大きさは、ここで測った大きさに固定する。中身に合わせて測り直させると、ここで測った幅とずれ、
        // 折り返しの数が変わって下が切れる
        list.minimumWidth = list.measuredWidth
        p.width = list.measuredWidth
        val height = minOf(list.measuredHeight, screen.height() - context.dp(32))
        p.height = height
        // ボタンの左に出す（画面の右端に寄せたボタンでも見切れないように）。下にはみ出すなら上へずらす
        p.x = (anchorX - list.measuredWidth - context.dp(8)).coerceAtLeast(context.dp(8))
        p.y = anchorY.coerceAtMost(screen.height() - height - context.dp(16)).coerceAtLeast(context.dp(16))
    }

    /** 左に設定の名前、右に選択肢を横に並べた1行。選択肢は幅が足りなければ折り返す（[WrapLayout]）。 */
    private fun choiceRow(entry: MenuEntry.Choice): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.TOP
        setPadding(0, context.dp(4), 0, context.dp(4))
        addView(TextView(context).apply {
            text = entry.label
            setTextColor(palette.onSurfaceMuted)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            minWidth = context.dp(68)
            // 選択肢の1行目と高さをそろえる
            setPadding(context.dp(2), context.dp(8), context.dp(6), 0)
        })
        addView(
            WrapLayout(context, gap = context.dp(6)).apply {
                entry.options.forEach { addView(chip(it)) }
                entry.more?.let { more ->
                    addView(chipView(more.label, more.checked).apply {
                        setOnClickListener {
                            OwnTouch.mark()
                            page = more
                            refresh()
                        }
                    })
                }
            },
            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
        )
    }

    /**
     * 選択肢1つ。押すと、選んでいなければ選び、メニューを描き直す。[inPage] ならページを閉じて元のメニューに戻る
     * （選んでいる選択肢を押しても戻る）。
     */
    private fun chip(option: Option, inPage: Boolean = false): View = chipView(option.label, option.checked, option.enabled).apply {
        setOnClickListener {
            OwnTouch.mark()
            if (!option.enabled) {
                option.disabledHint?.let { android.widget.Toast.makeText(context, it, android.widget.Toast.LENGTH_SHORT).show() }
                return@setOnClickListener
            }
            if (!option.checked) option.action()
            if (inPage) page = null
            refresh()
        }
    }

    /** 選択肢の見た目。選んでいるものは塗り、ほかは枠だけにする。選べないものは薄く出す。 */
    private fun chipView(label: String, checked: Boolean, enabled: Boolean = true): TextView = TextView(context).apply {
        text = label
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTextColor(if (checked) palette.onPrimary else palette.onSurface)
        gravity = Gravity.CENTER
        // 選択肢の名前は1行で出す（縦に潰さない）
        maxLines = 1
        setPadding(context.dp(12), context.dp(7), context.dp(12), context.dp(7))
        background = GradientDrawable().apply {
            cornerRadius = context.dp(16).toFloat()
            if (checked) setColor(palette.primary) else setStroke(context.dp(1), palette.outline)
        }
        if (!enabled) alpha = 0.4f
    }

    /** ページの中身：上に戻る行、その下に選択肢を折り返して並べる。 */
    private fun pageRows(open: OptionPage): List<View> = listOf(
        TextView(context).apply {
            text = "‹  ${open.title}"
            setTextColor(palette.primary)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(context.dp(2), context.dp(6), context.dp(4), context.dp(10))
            setOnClickListener {
                OwnTouch.mark()
                page = null
                refresh()
            }
        },
        WrapLayout(context, gap = context.dp(6)).apply {
            open.options().forEach { addView(chip(it, inPage = true)) }
            setPadding(0, 0, 0, context.dp(4))
        },
    )

    /** 押すとメニューを閉じて行う操作の行（終了）。 */
    private fun actionRow(entry: MenuEntry.Action): View = TextView(context).apply {
        text = entry.label
        setTextColor(palette.primary)
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
        page = null
        onDismiss?.invoke()
    }

    /** メニューを出しているか（自動で訳すとき、選んでいる途中にメニューを閉じないよう、その間は訳さない）。 */
    val isShowing: Boolean get() = root != null
}
