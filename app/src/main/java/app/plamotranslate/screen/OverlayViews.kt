package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import app.plamotranslate.log.Logs
import app.plamotranslate.overlay.OverlaySlot
import app.plamotranslate.overlay.OverlayPalette
import app.plamotranslate.overlay.dp
import app.plamotranslate.overlay.toBox
import app.plamotranslate.overlay.toRect
import app.plamotranslate.pipeline.layout.Rooms

/**
 * 今のステータスバーの高さ（px）。撮影画像の座標は画面の左上が原点なので、この高さより上はステータスバー。
 * 撮るたびに今の画面から読む。全画面のアプリ（漫画・動画など）でステータスバーが隠れていれば0、
 * 横画面なら横画面の高さになる（2026-11-09。それまでは縦持ちの固定値を読み、全画面のアプリで一番上の文字を捨てていた）。
 */
internal fun Context.statusBarHeight(): Int = runCatching {
    getSystemService(WindowManager::class.java).currentWindowMetrics.windowInsets
        .getInsets(WindowInsets.Type.statusBars()).top
}.getOrElse {
    // 読めない端末では、縦持ちのステータスバーの高さ（システムの既定値）を使う
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

    /**
     * 訳の枠を置く範囲。認識した枠は文字にぴったりなので、広げない枠には余白を足して原文の端が見えないようにする。
     * 余白を足した後に占めてよい範囲で切るので、隣の単位へはみ出さない（中核の `Rooms.drawn`）。広げた枠（[OverlaySlot.bubble]）はそのまま。
     */
    fun boxRect(context: Context, slot: OverlaySlot): Rect = Rooms.drawn(
        box = slot.rect.toBox(),
        grown = if (slot.bubble) slot.rect.toBox() else null,
        padX = context.dp(4),
        padY = context.dp(2),
        room = slot.room?.toBox(),
    ).toRect()

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
 * 自分のウィンドウ（フローティングボタン・メニュー・ステータス表示）に触れた時刻。[TouchWatcher] は自分のウィンドウに触れたときも「外側の操作」として
 * 知らせるので、ほかのアプリへの操作と区別するのに使う。
 */
internal object OwnTouch {
    @Volatile
    var lastAt = 0L
        private set

    fun mark() {
        lastAt = SystemClock.uptimeMillis()
    }

    /** 直近 [withinMillis] 以内に自分のウィンドウに触れたか。 */
    fun recent(withinMillis: Long = 300): Boolean = SystemClock.uptimeMillis() - lastAt <= withinMillis
}

/**
 * 画面のどこかに触れたことを知るための、見えない1画素のウィンドウ（2026-11-12、作者の要望「自動翻訳で訳している途中に画面を操作したら、
 * 全処理を取り消す」）。`FLAG_WATCH_OUTSIDE_TOUCH` で、ウィンドウの外（ほかのアプリ）に触れるたびにイベントを受ける。
 * 撮影の間も隠さずに置いておくので、撮っている最中の操作にも気づける（ステータス表示は撮影の間は消えている）。
 * 自分のウィンドウに触れたときもイベントが来るので、少し待ってから [OwnTouch] で除く。
 */
@SuppressLint("ClickableViewAccessibility")
internal class TouchWatcher(context: Context, private val windowManager: WindowManager, onTouch: () -> Unit) {
    private val view = View(context).apply {
        setOnTouchListener { v, e ->
            if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                // 自分のボタン・メニューへの操作は、そのウィンドウの処理が先に OwnTouch に記録するのを待ってから除く
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

internal fun overlayParams(width: Int, height: Int, gravity: Int) = WindowManager.LayoutParams(
    width, height,
    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
    PixelFormat.TRANSLUCENT,
).apply { this.gravity = gravity }

/** 撮影した画面の座標そのままに置くためのウィンドウの設定。ステータスバーや切り欠きの分ずらされないようにする。 */
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
 * 訳を元の文字の位置に重ねる（docs/設計/画面翻訳.md「訳文の重ね表示」）。
 * 訳の枠は全部、枠全体を囲む長方形の1つの触れないウィンドウ（[layer]）の中に並べる。ウィンドウの数は枠の数に依らず1つにする
 * （2026-11-13。それまでは枠1つにウィンドウ1つを置いていて、訳す単位の多い画面で REDMAGIC の「1つのアプリのウィンドウが60を超えたら
 * System.exit する」決まりに当たり、画面翻訳中にアプリが終わった。docs/資料/端末の能力.md）。
 * ウィンドウを画面全体にしないのは、ほかのアプリから見て覆われる範囲（触れた操作に「覆われている」フラグが付く範囲）を、枠のある所までに
 * とどめるため。
 *
 * 訳の枠は触れても反応せず、触れた操作は奥のアプリへそのまま届く（2026-11-12、作者の指摘「訳をタップすると1つだけ消える機能より、
 * 字幕の上をスワイプして奥のDiscordの投稿をスクロールできる方が嬉しい。消すのはフローティングボタンで一括でよい」）。
 * Android 12以降は、ほかのアプリの重ね表示を通り抜ける操作は、ウィンドウの不透明度がシステムの上限（既定0.8）以下でないと捨てられる。
 * そのため枠のウィンドウの不透明度を上限に合わせ、原文がわずかに透ける。
 *
 * 画面のどこかに触れたときの扱い（消す・取り消す・枠の下をのぞく）は、サービスが [TouchWatcher] で受けて決める。
 */
class TranslationOverlay(private val context: Context, private val windowManager: WindowManager) {
    // 横書きは HorizontalTextView、縦書きは VerticalTextView。訳の追記・置き換えだけ共通に扱う
    private val boxes = mutableListOf<View>()
    private val boxRects = mutableListOf<Rect>()

    // 訳の枠を並べる、枠全体を囲む1つの触れないウィンドウと、それを置いた先（ユーザー補助のサービスの WindowManager か、自分の
    // WindowManager）。枠を出すときに置き、消すとき（撮る前・取り消し）に同じ先から外す
    private var layer: FrameLayout? = null
    private var layerHost: WindowManager? = null
    private var statusView: TextView? = null

    /**
     * 訳の枠を透かさないか（設定の画面の「訳の枠を透かさない」。[OverlayAccessibilityService]）。
     * true でもユーザー補助のサービスがつながっていなければ、今までどおり不透明度0.8で置く。
     */
    var opaqueWithAccessibility: () -> Boolean = { false }

    /** ステータス表示をタップしたとき（訳している途中なら取り消してから消すため、サービスが受ける）。無ければ消すだけ。 */
    var onStatusTap: (() -> Unit)? = null

    /** 訳の枠を並べるウィンドウを置けなかった（重ね表示の許可を途中で外したときなど）。枠は1つも出ていない。 */
    var onBoxesUnplaced: (() -> Unit)? = null

    var onStatusLayout: (() -> Unit)? = null

    /** 訳の枠の範囲（画面の座標）。 */
    val boxBounds: List<Rect> get() = boxRects.toList()

    /** 訳の枠を一時的に隠す・戻す（枠の下の画面をのぞくとき）。 */
    fun setBoxesVisible(visible: Boolean) {
        layer?.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }

    /** 通り抜ける操作が捨てられない、ウィンドウの不透明度の上限。 */
    private val passThroughAlpha = runCatching {
        context.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch
    }.getOrDefault(0.8f)

    /** 訳の枠かステータス表示を出しているか。 */
    val isShowing: Boolean get() = boxes.isNotEmpty() || statusView != null

    /** [slots] の位置に空の訳の枠を並べる。[status] は画面上部のステータス表示。 */
    fun show(slots: List<OverlaySlot>, status: String) {
        clear()
        // ユーザー補助の重ね表示なら不透明のまま操作を通せる。ウィンドウはそのサービスの WindowManager から置く
        val a11y = if (opaqueWithAccessibility()) OverlayAccessibilityService.instance else null
        val host = a11y?.getSystemService(WindowManager::class.java) ?: windowManager
        val viewContext: Context = a11y ?: context
        val type = if (a11y != null) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        val rects = slots.map { OverlayStyle.boxRect(context, it) }
        val layer = placeLayer(rects, host, viewContext, type, alpha = if (a11y != null) 1f else passThroughAlpha)
        if (layer == null && rects.isNotEmpty()) {
            // 置けなかったことをコールバックで伝えて終える（受けた側が取り消して上部に出すので、「翻訳中」は出さない）
            onBoxesUnplaced?.invoke()
            return
        }
        if (layer != null) {
            val origin = layer.second
            for ((slot, rect) in slots.zip(rects)) {
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
                // ウィンドウは枠全体を囲む長方形に置くので、枠はその左上からの位置に並べる
                layer.first.addView(box, FrameLayout.LayoutParams(rect.width(), rect.height()))
                box.x = (rect.left - origin.left).toFloat()
                box.y = (rect.top - origin.top).toFloat()
                boxes += box
                boxRects += rect
            }
        }
        showStatus(status)
    }

    /**
     * 枠全体を囲む長方形に、枠を並べるウィンドウを置く。ウィンドウと、その画面での範囲を返す。枠が無いか、置けなければ null（枠は出さない）。
     */
    private fun placeLayer(rects: List<Rect>, host: WindowManager, viewContext: Context, type: Int, alpha: Float): Pair<FrameLayout, Rect>? {
        if (rects.isEmpty()) return null
        val bounds = Rect(rects.first()).apply { rects.drop(1).forEach { union(it) } }
        val params = absoluteParams(bounds, WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE, type).apply { this.alpha = alpha }
        val created = FrameLayout(viewContext)
        return runCatching { host.addView(created, params) }
            .onFailure { Logs.w("画面翻訳: 訳の枠のウィンドウを置けませんでした", it) }
            .map {
                layer = created
                layerHost = host
                created to bounds
            }.getOrNull()
    }

    /** 自分が画面に出している範囲（訳の枠とステータス表示）。画面が変わったかを見るとき、ここは比べない。 */
    fun occupiedRects(): List<Rect> = buildList {
        addAll(boxRects)
        statusBounds()?.let(::add)
    }

    /** 訳のウィンドウ全体とステータス表示。別の重ね表示を置くときに使う。 */
    fun windowBounds(): List<Rect> = buildList {
        boxRects.firstOrNull()?.let { first -> add(Rect(first).apply { boxRects.forEach { union(it) } }) }
        statusBounds()?.let(::add)
    }

    private fun statusBounds(): Rect? = statusView?.takeIf { it.isLaidOut }?.let { view ->
        val xy = IntArray(2).also(view::getLocationOnScreen)
        Rect(xy[0], xy[1], xy[0] + view.width, xy[1] + view.height)
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

    /** 画面上部の中央に、件数・所要時間などを小さく出す。訳以外に触れたら全体を消すのにも使う。出せなければ false。 */
    @SuppressLint("ClickableViewAccessibility")
    fun showStatus(text: String): Boolean {
        val palette = OverlayPalette.current(context)
        val view = statusView ?: TextView(context).apply {
            setTextColor(palette.onSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            setPadding(context.dp(10), context.dp(4), context.dp(10), context.dp(4))
            background = GradientDrawable().apply {
                setColor(palette.surfaceRaised)
                setStroke(context.dp(1), palette.outline)
                cornerRadius = context.dp(12).toFloat()
            }
            // ほかの場所に触れたときの扱いは、サービスが TouchWatcher で受けて決める
            setOnTouchListener { _, e ->
                OwnTouch.mark()
                if (e.actionMasked == MotionEvent.ACTION_UP) onStatusTap?.invoke() ?: clear()
                true
            }
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> onStatusLayout?.invoke() }
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(4)
            }
            // 置けなければ（重ね表示の許可が無いときなど）出さない。呼ぶ側が別の手段で知らせる
            if (runCatching { windowManager.addView(this, params) }.onFailure { Logs.w("画面翻訳: ステータス表示を置けませんでした", it) }.isFailure) {
                return false
            }
            statusView = this
        }
        view.text = "$text　（タップで消す）"
        return true
    }

    /** 訳の枠とステータス表示を消し、枠のウィンドウも外す。 */
    fun clear() {
        // 外せなかったウィンドウは画面に残るので、記録する（2026-11-12、消したはずの訳の枠が撮影に写ったことがあった）
        layer?.let { v -> runCatching { layerHost?.removeView(v) }.onFailure { Logs.w("画面翻訳: 訳の枠のウィンドウを外せませんでした", it) } }
        layer = null
        layerHost = null
        boxes.clear()
        boxRects.clear()
        statusView?.let { runCatching { windowManager.removeView(it) } }
        statusView = null
    }
}
