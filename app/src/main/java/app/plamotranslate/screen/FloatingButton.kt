package app.plamotranslate.screen

import android.annotation.SuppressLint
import android.content.Context
import android.content.res.Configuration
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import app.plamotranslate.R
import app.plamotranslate.ThemePreferences
import app.plamotranslate.overlay.OverlayPalette
import app.plamotranslate.overlay.dp
import kotlin.math.abs
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/** 画面の端に浮かぶ丸いボタン。ドラッグで動かせる。タップで翻訳、長押しで言語のメニュー（終了もここ）。 */
@SuppressLint("ClickableViewAccessibility")
class FloatingButton(
    private val context: Context,
    private val windowManager: WindowManager,
    onTap: () -> Unit,
    onLongPress: () -> Unit,
) {
    /** 鳥の入る丸の大きさ。 */
    private val faceSize = context.dp(56)

    /** ボタンのウィンドウの大きさ。丸の外に、自動のときの輪を描く分の余白を取る。 */
    private val size = faceSize + context.dp(10)

    /** 今の画面の大きさ。画面を回したら [fitToScreen] で更新する。 */
    private var screen = windowManager.maximumWindowMetrics.bounds
    private val params = overlayParams(size, size, Gravity.TOP or Gravity.START).apply {
        x = screen.width() - size - context.dp(3)
        y = screen.height() / 3
    }

    /**
     * アプリのロゴ。アプリのアイコンの背景と前景を、アイコンの色のまま重ねて描く（ロゴはテーマで色を変えない。
     * アプリのアイコンを変えれば、ここも変わる）。文字の「訳」は使わない（docs/設計/デザイン言語.md「アイコン」）。
     */
    private val markBackground = ImageView(context).apply { setImageResource(R.drawable.ic_launcher_background) }
    private val mark = ImageView(context).apply { setImageResource(R.drawable.ic_launcher_foreground) }

    /**
     * 原文の言語を決めているときだけ出す、右下の小さなバッジ（中・韓・英などの1文字か、言語コードの2文字。ScreenLanguage.kt）。
     * 文字の大きさは端末の設定で変わるので、幅は文字に合わせて広げ（最小は高さと同じ丸）、角を丸めた形にする。
     */
    private val badge = TextView(context).apply {
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        includeFontPadding = false
        minWidth = context.dp(18)
        setPadding(context.dp(3), 0, context.dp(3), 0)
        visibility = View.GONE
    }

    /** 鳥の入る丸。ロゴは、この丸の形で切り抜く（輪や縁の線が鳥に重ならない）。影は付けない（平らなボタン。縁で見分ける）。 */
    private val face = FrameLayout(context).apply {
        clipToOutline = true
        // アイコンのグリッド108のうち、真ん中の72（アダプティブアイコンの見える範囲）を丸いっぱいに描く。
        // 108全体を丸に収めると図柄が小さくなる。はみ出した外周は丸で切れる
        val full = faceSize * 108 / 72
        addView(markBackground, FrameLayout.LayoutParams(full, full, Gravity.CENTER))
        addView(mark, FrameLayout.LayoutParams(full, full, Gravity.CENTER))
    }
    private val view = FrameLayout(context).apply {
        contentDescription = "画面翻訳"
        clipChildren = false
        addView(face, FrameLayout.LayoutParams(faceSize, faceSize, Gravity.CENTER))
        addView(badge, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, context.dp(18), Gravity.BOTTOM or Gravity.END))
    }

    /** 自動で訳すか。自動なら、丸の外側にもう1本の輪を出す（丸は縮めない）。 */
    private var auto = false

    /** 端末のダーク設定（[configurationChanged] で受け取る）。 */
    private val systemDark = MutableStateFlow(ThemePreferences.systemDark(context.resources.configuration))

    /** テーマの購読。[remove] で止める。 */
    private val themeScope = MainScope()

    /** 端末の設定が変わった（サービスの onConfigurationChanged から）。明暗が変わってダークで描くかが変われば塗り直す。 */
    fun configurationChanged(config: Configuration) {
        systemDark.value = ThemePreferences.systemDark(config)
    }

    /**
     * 今のテーマの色で塗り直す（作ったとき、テーマや端末の明暗が変わったとき、訳し方を変えたとき）。ロゴはアイコンの色のままで、
     * その周りの縁・輪・バッジをテーマのプライマリカラーにする。縁は、暗い画面の上でも紺のロゴが埋もれないようにする（docs/設計/デザイン言語.md「色」）。
     */
    fun applyPalette() {
        val palette = OverlayPalette.current(context)
        badge.setTextColor(palette.onPrimary)
        badge.background = GradientDrawable().apply {
            cornerRadius = context.dp(9).toFloat()
            setColor(palette.primary)
        }
        // 丸の形（切り抜きの形）と、ロゴの上に重ねる縁
        face.background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(palette.surfaceRaised) }
        face.foreground = GradientDrawable().apply { shape = GradientDrawable.OVAL; setStroke(context.dp(2), palette.primary) }
        view.background = if (!auto) null else GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setStroke(context.dp(2), palette.primary)
        }
    }

    init {
        // 作ったときはその場で塗る（購読の最初の値は後で届くので、それまで素の見た目にしない）
        applyPalette()
        // 設定の「画面の色」と端末の明暗のどちらが変わっても、ダークで描くかが変わったときだけ塗り直す（入口はこの購読の1つ）
        themeScope.launch {
            combine(ThemePreferences.mode, systemDark) { mode, system -> ThemePreferences.dark(mode, system) }
                .distinctUntilChanged()
                .collect { applyPalette() }
        }
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

    /** 訳し方を出す。[language] は原文の言語のバッジ（自動なら null）、[auto] は自動で訳すか。 */
    fun setMode(language: String?, auto: Boolean) {
        badge.text = language.orEmpty()
        badge.visibility = if (language == null) View.GONE else View.VISIBLE
        this.auto = auto
        applyPalette()
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
    fun remove() {
        themeScope.cancel()
        runCatching { windowManager.removeView(view) }
    }
    fun setVisible(visible: Boolean) {
        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
    }
}
