package app.plamotranslate.screen

import android.content.Context
import android.graphics.Rect
import android.hardware.input.InputManager
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import androidx.compose.ui.platform.ComposeView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import app.plamotranslate.ComputeMonitor
import app.plamotranslate.log.Logs
import app.plamotranslate.overlay.dp

/** 貸出の小さい図を、訳の枠や操作に重ならない隅に置く。 */
internal class ScreenComputeOverlay(context: Context, private val windows: WindowManager) : SavedStateRegistryOwner {
    override val lifecycle = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    override val savedStateRegistry get() = savedState.savedStateRegistry

    private val params = overlayParams(context.dp(190), context.dp(72), Gravity.TOP or Gravity.LEFT).apply {
        flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        fitInsetsTypes = 0
        layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        alpha = context.getSystemService(InputManager::class.java)?.maximumObscuringOpacityForTouch ?: 0.8f
    }
    private val margin = context.dp(8)
    private val view = ComposeView(context).apply {
        setViewTreeLifecycleOwner(this@ScreenComputeOverlay)
        setViewTreeSavedStateRegistryOwner(this@ScreenComputeOverlay)
        setContent { ComputeMonitor(compact = true) }
    }
    private var placed: Rect? = null
    val bounds: Rect? get() = placed?.takeIf { view.visibility == View.VISIBLE }

    init {
        savedState.performRestore(null)
        lifecycle.currentState = Lifecycle.State.STARTED
    }

    fun place(avoid: List<Rect>, hidden: Boolean) {
        val next = if (hidden) null else availableCorner(avoid)
        if (next == null) {
            view.visibility = View.INVISIBLE
            return
        }
        if (placed != next) {
            params.x = next.left
            params.y = next.top
            val result = runCatching {
                if (placed == null) windows.addView(view, params) else windows.updateViewLayout(view, params)
            }
            if (result.isFailure) {
                Logs.w("画面翻訳: 貸出の図を置けませんでした", result.exceptionOrNull())
                view.visibility = View.INVISIBLE
                return
            }
            placed = next
        }
        view.visibility = View.VISIBLE
    }

    private fun availableCorner(avoid: List<Rect>): Rect? {
        val metrics = windows.maximumWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        val left = insets.left + margin
        val top = insets.top + margin
        val right = metrics.bounds.width() - insets.right - margin - params.width
        val bottom = metrics.bounds.height() - insets.bottom - margin - params.height
        if (right < left || bottom < top) return null
        // 画面の下端・上端を先に試し、空きがなければほかの表示の縁に沿う位置も試す。
        val xs = (listOf(left, right) + avoid.flatMap { listOf(it.left - params.width, it.right) }).filter { it in left..right }.distinct()
        val ys = (listOf(bottom, top) + avoid.flatMap { listOf(it.top - params.height, it.bottom) }).filter { it in top..bottom }.distinct()
        return ys.asSequence().flatMap { y -> xs.map { x -> Rect(x, y, x + params.width, y + params.height) } }
            .firstOrNull { candidate -> avoid.none { Rect.intersects(it, candidate) } }
    }

    fun remove() {
        if (placed != null) runCatching { windows.removeView(view) }
        view.disposeComposition()
        lifecycle.currentState = Lifecycle.State.DESTROYED
    }
}
