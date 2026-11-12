package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.os.SystemClock
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import app.plamotranslate.overlay.OverlaySlot
import app.plamotranslate.overlay.dp
import kotlin.math.abs

/**
 * 今の状態バーの高さ（px）。撮影画像の座標は画面の左上が原点なので、この高さより上は状態バー。
 * 撮るたびに今の画面から読む。全画面のアプリ（漫画・動画など）で状態バーが隠れていれば0、
 * 横画面なら横画面の高さになる（2026-11-09。それまでは縦持ちの固定値を読み、全画面のアプリで一番上の文字を捨てていた）。
 */
internal fun Context.statusBarHeight(): Int = runCatching {
    getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
        .getInsets(WindowInsets.Type.statusBars()).top
}.getOrElse {
    // 読めない端末では、縦持ちの状態バーの高さ（システムの既定値）を使う
    val id = resources.getIdentifier("status_bar_height", "dimen", "android")
    if (id > 0) resources.getDimensionPixelSize(id) else 0
}

/** 訳の枠の見た目。画面の重ね表示と評価の描画（BenchActivity）でそろえる。 */
internal object OverlayStyle {
    /** 訳の文字の大きさの範囲（sp）。枠に収まる最大の大きさを選ぶ。 */
    const val MIN_SP = 7
    const val MAX_SP = 22

    /** 横書きの訳の枠の、左右の内側の余白（dp）。 */
    const val H_PADDING_DP = 2

    /** 認識した枠は文字にぴったりなので、少し広げて原文の端が見えないようにする。吹き出しまで広げた枠（[OverlaySlot.bubble]）はそのまま。 */
    fun padded(context: Context, rect: Rect): Rect = Rect(rect).apply { inset(-context.dp(4), -context.dp(2)) }

    /** 訳の枠を置く範囲。 */
    fun boxRect(context: Context, slot: OverlaySlot): Rect = if (slot.bubble) Rect(slot.rect) else padded(context, slot.rect)

    /**
     * 訳の文字は太字にし、背景色でぼかした縁取りを付ける（2026-11-12、作者の指摘「漫画の小さな文字の訳が読みにくい」）。
     * 枠から透ける元の文字の線と、訳の線が重なって見分けにくかったので、訳の線の周りを背景色で縁取って分ける。
     */
    val TYPEFACE: Typeface = Typeface.DEFAULT_BOLD

    /** 縁取りのぼかしの半径（dp）。 */
    const val HALO_DP = 2.5f

    fun applyHalo(paint: Paint, background: Int, density: Float) {
        paint.typeface = TYPEFACE
        paint.setShadowLayer(HALO_DP * density, 0f, 0f, background)
    }
}

/**
 * 自分の窓（浮遊ボタン・メニュー・上部の小さな表示）に触れた時刻。[TouchWatcher] は自分の窓に触れたときも「外側の操作」として
 * 知らせるので、ほかのアプリへの操作と区別するのに使う。
 */
internal object OwnTouch {
    @Volatile
    var lastAt = 0L
        private set

    fun mark() {
        lastAt = SystemClock.uptimeMillis()
    }

    /** 直近 [withinMillis] 以内に自分の窓に触れたか。 */
    fun recent(withinMillis: Long = 300): Boolean = SystemClock.uptimeMillis() - lastAt <= withinMillis
}

/**
 * 画面のどこかに触れたことを知るための、見えない1画素の窓（2026-11-12、作者の要望「自動翻訳で訳している途中に画面を操作したら、
 * 全処理を取り消す」）。`FLAG_WATCH_OUTSIDE_TOUCH` で、窓の外（ほかのアプリ）に触れるたびに知らせを受ける。
 * 撮影の間も隠さずに置いておくので、撮っている最中の操作にも気づける（上部の小さな表示は撮影の間は消えている）。
 * 自分の窓に触れたときも知らせが来るので、少し待ってから [OwnTouch] で除く。
 */
@SuppressLint("ClickableViewAccessibility")
internal class TouchWatcher(context: Context, private val windowManager: WindowManager, onTouch: () -> Unit) {
    private val view = View(context).apply {
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // 自分のボタン・メニューへの操作は、その窓の処理が先に OwnTouch に記録するのを待ってから除く
                v.postDelayed({ if (!OwnTouch.recent()) onTouch() }, OWN_TOUCH_SETTLE_MS)
            }
            false
        }
    }

    fun show() = windowManager.addView(view, WindowManager.LayoutParams(
        1, 1,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = 0
        y = 0
    })

    fun remove() = runCatching { windowManager.removeView(view) }

    private companion object {
        const val OWN_TOUCH_SETTLE_MS = 80L
    }
}

private fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
    width, height,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply { this.gravity = gravity }

/** 画面の端に浮かぶ丸いボタン。ドラッグで動かせる。タップで翻訳、長押しで言語のメニュー（終了もここ）。 */
@SuppressLint("ClickableViewAccessibility")
class FloatingButton(
    private val context: Context,
    private val windowManager: WindowManager,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    private val size = context.dp(56)

    /** 今の画面の大きさ。画面を回したら [fitToScreen] で更新する。 */
    private var screen = windowManager.maximumWindowMetrics.bounds
    private val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
        x = screen.width() - size - context.dp(8)
        y = screen.height() / 3
    }
    private val view = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(Color.argb(220, 98, 0, 238))
        }
        elevation = context.dp(6).toFloat()
    }

    init {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var longPressed = false
        val longPress = Runnable { longPressed = true; onLongPress() }
        view.setOnTouchListener { _, e ->
            OwnTouch.mark()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y
                    dragging = false; longPressed = false
                    view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                        view.removeCallbacks(longPress)
                    }
                    if (dragging) {
                        // 画面の外へ出さない
                        params.x = (startX + dx.toInt()).coerceIn(0, (screen.width() - size).coerceAtLeast(0))
                        params.y = (startY + dy.toInt()).coerceIn(0, (screen.height() - size).coerceAtLeast(0))
                        windowManager.updateViewLayout(view, params)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    view.removeCallbacks(longPress)
                    if (!dragging && !longPressed) onTap()
                }
                MotionEvent.ACTION_CANCEL -> view.removeCallbacks(longPress)
            }
            true
        }
    }

    /** ボタンの文字。自動なら「訳」、言語を決めたら「訳」の下に言語を小さく出す。 */
    fun setLabel(label: String) {
        view.text = label
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (label.length > 1) 13f else 20f)
    }

    /**
     * 画面を回したら、ボタンを今の画面の同じ割合の位置へ移す（右端にあれば右端のまま）。
     * 移さないと、前の向きの座標のまま画面の中ほどや外に残る。
     */
    fun fitToScreen() {
        val now = windowManager.maximumWindowMetrics.bounds
        if (now.width() == screen.width() && now.height() == screen.height()) return
        val fx = params.x.toFloat() / (screen.width() - size).coerceAtLeast(1)
        val fy = params.y.toFloat() / (screen.height() - size).coerceAtLeast(1)
        screen = now
        params.x = (fx * (now.width() - size)).toInt().coerceIn(0, (now.width() - size).coerceAtLeast(0))
        params.y = (fy * (now.height() - size)).toInt().coerceIn(0, (now.height() - size).coerceAtLeast(0))
        runCatching { windowManager.updateViewLayout(view, params) }
    }

    /** 自動で訳すのを止めているときは薄くする。 */
    fun setActive(active: Boolean) {
        view.alpha = if (active) 1f else 0.4f
    }

    /** ボタンが画面に占める範囲（画面が変わったかを見るとき、ここは比べない）。 */
    val bounds: Rect get() = Rect(params.x, params.y, params.x + size, params.y + size)

    /** メニューを出す位置の目安（ボタンの左上）。 */
    val position: Pair<Int, Int> get() = params.x to params.y

    fun show() = windowManager.addView(view, params)
    fun remove() = runCatching { windowManager.removeView(view) }
    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}

/** 撮影した画面の座標そのままに置くための窓の設定。状態バーや切り欠きの分ずらされないようにする。 */
private fun absoluteParams(rect: Rect, flags: Int, type: Int = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY) = WindowManager.LayoutParams(
    rect.width(), rect.height(),
    type,
    flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
    PixelFormat.TRANSLUCENT,
).apply {
    gravity = Gravity.TOP or Gravity.START
    x = rect.left
    y = rect.top
    fitInsetsTypes = 0
    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
}

/**
 * 訳を元の文字の位置に重ねる（docs/05_画面翻訳.md「訳文の重ね表示」）。
 * 訳1つにつき小さな窓を1つ置く。全画面の窓にすると下のアプリを操作できなくなるため。
 *
 * 訳の枠は触れても反応せず、触れた操作は奥のアプリへそのまま届く（2026-11-12、作者の指摘「訳をタップすると1つだけ消える機能より、
 * 字幕の上をスワイプして奥のDiscordの投稿をスクロールできる方が嬉しい。消すのは浮遊ボタンで一括でよい」）。
 * Android 12以降は、ほかのアプリの重ね表示を通り抜ける操作は、窓の不透明度がシステムの上限（既定0.8）以下でないと捨てられる。
 * そのため枠の窓の不透明度を上限に合わせ、原文がわずかに透ける。
 *
 * 画面のどこかに触れたときの扱い（消す・取り消す・枠の下をのぞく）は、サービスが [TouchWatcher] で受けて決める。
 */
class TranslationOverlay(private val context: Context, private val windowManager: WindowManager) {
    // 横書きは HorizontalTextView、縦書きは VerticalTextView。訳の追記・置き換えだけ共通に扱う
    private val boxes = mutableListOf<View>()
    private val boxRects = mutableListOf<Rect>()

    // 枠ごとに置いた先（ユーザー補助のサービスの WindowManager か、自分の WindowManager）。消すときに同じ先から外す
    private val boxHosts = mutableListOf<WindowManager>()
    private var statusView: TextView? = null

    /**
     * 訳の枠を透かさないか（設定の画面の「訳の枠を透かさない」。[OverlayAccessibilityService]）。
     * true でもユーザー補助のサービスがつながっていなければ、今までどおり不透明度0.8で置く。
     */
    var opaqueWithAccessibility: () -> Boolean = { false }

    /** 上部の小さな表示をタップしたとき（訳している途中なら取り消してから消すため、サービスが受ける）。無ければ消すだけ。 */
    var onStatusTap: (() -> Unit)? = null

    /** 訳の枠の範囲（画面の座標）。 */
    val boxBounds: List<Rect> get() = boxRects.toList()

    /** 訳の枠を一時的に隠す・戻す（枠の下の画面をのぞくとき）。 */
    fun setBoxesVisible(visible: Boolean) {
        boxes.forEach { it.visibility = if (visible) View.VISIBLE else View.INVISIBLE }
    }

    /** 通り抜ける操作が捨てられない、窓の不透明度の上限。 */
    private val passThroughAlpha = runCatching {
        context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch
    }.getOrDefault(0.8f)

    /** 訳の枠か上部の小さな表示を出しているか。 */
    val isShowing: Boolean get() = boxes.isNotEmpty() || statusView != null

    /** [slots] の位置に空の訳の枠を並べる。[status] は画面上部の小さな表示。 */
    fun show(slots: List<OverlaySlot>, status: String) {
        clear()
        // ユーザー補助の重ね表示なら不透明のまま操作を通せる。窓はそのサービスの WindowManager から置く
        val a11y = if (opaqueWithAccessibility()) OverlayAccessibilityService.instance else null
        val host = a11y?.getSystemService(WindowManager::class.java) ?: windowManager
        val viewContext: Context = a11y ?: context
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        for (slot in slots) {
            // 吹き出しまで広げた枠の横書きは、行を真ん中に寄せる
            val box = if (slot.vertical) VerticalTextView(viewContext) else HorizontalTextView(viewContext, center = slot.bubble)
            // 元の画面の背景色と文字色にそろえる（2026-11-05、作者の要望）。太字と背景色の縁取りで読みやすくする（OverlayStyle）
            box.setBackgroundColor(slot.colors.background)
            when (box) {
                is VerticalTextView -> {
                    box.textColor = slot.colors.text
                    box.setHalo(slot.colors.background)
                }
                is HorizontalTextView -> {
                    box.textColor = slot.colors.text
                    box.setHalo(slot.colors.background)
                }
            }
            val rect = OverlayStyle.boxRect(context, slot)
            val params = absoluteParams(rect, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, type).apply {
                alpha = if (a11y != null) 1f else passThroughAlpha
            }
            runCatching { host.addView(box, params) }.onFailure { return@onFailure }.onSuccess {
                boxes += box
                boxRects += rect
                boxHosts += host
            }
        }
        showStatus(status)
    }

    /** 自分が画面に出している範囲（訳の枠と上部の小さな表示）。画面が変わったかを見るとき、ここは比べない。 */
    fun occupiedRects(): List<Rect> = buildList {
        addAll(boxRects)
        statusView?.takeIf { it.isAttachedToWindow }?.let { v ->
            val xy = IntArray(2).also(v::getLocationOnScreen)
            add(Rect(xy[0], xy[1], xy[0] + v.width, xy[1] + v.height))
        }
    }

    // 文字が変わるたびに、枠に収まる大きさを選び直す（HorizontalTextView・VerticalTextView）
    fun appendTranslation(index: Int, delta: String) {
        when (val box = boxes.getOrNull(index)) {
            is VerticalTextView -> box.append(delta)
            is HorizontalTextView -> box.append(delta)
        }
    }

    fun setTranslation(index: Int, text: String) {
        when (val box = boxes.getOrNull(index)) {
            is VerticalTextView -> box.text = text
            is HorizontalTextView -> box.text = text
        }
    }

    /** 画面上部の中央に、件数・所要時間などを小さく出す。訳以外に触れると全体を消す合図にも使う。 */
    @SuppressLint("ClickableViewAccessibility")
    fun showStatus(text: String) {
        val view = statusView ?: TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
            background = GradientDrawable().apply {
                setColor(Color.argb(200, 98, 0, 238))
                cornerRadius = context.dp(12).toFloat()
            }
            // ほかの場所に触れたときの扱いは、サービスが TouchWatcher で受けて決める
            setOnTouchListener { _, e ->
                OwnTouch.mark()
                if (e.actionMasked == MotionEvent.ACTION_UP) onStatusTap?.invoke() ?: clear()
                true
            }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(4)
            }
            windowManager.addView(this, params)
            statusView = this
        }
        view.text = "$text　（タップで消す）"
    }

    fun clear() {
        boxes.forEachIndexed { i, box -> runCatching { boxHosts[i].removeView(box) } }
        boxes.clear()
        boxRects.clear()
        boxHosts.clear()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
    }
}

/** 浮遊ボタンのメニューの1行。[Header] は見出し（押せない）、[Item] は選べる行。 */
internal sealed interface MenuEntry {
    class Header(val label: String) : MenuEntry
    class Item(val label: String, val checked: Boolean = false, val enabled: Boolean = true, val action: () -> Unit) : MenuEntry
}

/**
 * 浮遊ボタンの長押しで出すメニュー（2026-11-05、作者の要望）。原文の言語（自動・中国語・韓国語・英語）と終了を選ぶ。
 * 2026-11-12に、認識モードと翻訳モデルの見出しと行を足した（作者の要望）。
 * メニューの外に触れたら閉じる。行が多く画面に収まらないときはスクロールする。
 */
internal class ScreenMenu(private val context: Context, private val windowManager: WindowManager) {
    private var root: View? = null

    @SuppressLint("ClickableViewAccessibility")
    fun show(anchorX: Int, anchorY: Int, entries: List<MenuEntry>) {
        dismiss()
        val list = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(context.dp(6), context.dp(6), context.dp(6), context.dp(6))
            background = GradientDrawable().apply {
                setColor(Color.argb(240, 32, 32, 40))
                cornerRadius = context.dp(12).toFloat()
            }
        }
        for (entry in entries) when (entry) {
            is MenuEntry.Header -> list.addView(TextView(context).apply {
                text = entry.label
                setTextColor(Color.argb(170, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setPadding(context.dp(14), context.dp(8), context.dp(20), context.dp(2))
            })
            is MenuEntry.Item -> list.addView(TextView(context).apply {
                text = if (entry.checked) "● ${entry.label}" else "　 ${entry.label}"
                setTextColor(if (entry.enabled) Color.WHITE else Color.argb(110, 255, 255, 255))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                setPadding(context.dp(14), context.dp(9), context.dp(20), context.dp(9))
                if (entry.enabled) setOnClickListener {
                    OwnTouch.mark()
                    dismiss()
                    entry.action()
                }
            })
        }
        // 項目が増えて横画面などで収まらないときのために、画面の高さに合わせてスクロールさせる
        val screen = windowManager.maximumWindowMetrics.bounds
        val view = ScrollView(context).apply {
            addView(list)
            setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) dismiss() else OwnTouch.mark()
                false
            }
        }
        list.measure(
            View.MeasureSpec.makeMeasureSpec(screen.width(), View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val maxHeight = screen.height() - context.dp(32)
        val height = minOf(list.measuredHeight, maxHeight)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, height,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // ボタンの左に出す（画面の右端に寄せたボタンでも見切れないように）。下にはみ出すなら上へずらす
            x = (anchorX - list.measuredWidth - context.dp(8)).coerceAtLeast(context.dp(8))
            y = anchorY.coerceAtMost(screen.height() - height - context.dp(16)).coerceAtLeast(context.dp(16))
        }
        windowManager.addView(view, params)
        root = view
    }

    fun dismiss() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
    }

    /** メニューを出しているか（自動で訳すとき、選んでいる途中にメニューを閉じないよう、その間は訳さない）。 */
    val isShowing: Boolean get() = root != null
}