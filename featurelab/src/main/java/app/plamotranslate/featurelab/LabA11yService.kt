package app.plamotranslate.featurelab

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityWindowInfo

/**
 * 機能テストのユーザー補助（問い1・2・3・4。docs/12_機能テスト.md）。
 * - 届いた出来事の種類・アプリ・変化の種類・スクロール量と、キー入力をログに残す（文字や中身は読まない）。
 * - 紫の重ね表示（TYPE_ACCESSIBILITY_OVERLAY）を出し入れする。
 * - 画面の撮影（takeScreenshot）と、前面のアプリの窓だけの撮影（takeScreenshotOfWindow）を試し、紫・水色が写るかを見る。
 * PC から `adb shell am broadcast -a app.plamotranslate.featurelab.CMD --es cmd <命令>` で操作できる（[COMMANDS]）。
 */
class LabA11yService : AccessibilityService() {

    private var overlay: View? = null
    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            run(intent.getStringExtra("cmd").orEmpty())
        }
    }

    override fun onServiceConnected() {
        instance = this
        registerReceiver(receiver, IntentFilter(ACTION_CMD), RECEIVER_EXPORTED)
        Lab.log("ユーザー補助: つながった")
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent) {
        if (e.packageName == packageName) return
        val type = AccessibilityEvent.eventTypeToString(e.eventType)
        val extra = when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_SCROLLED ->
                "dx=${e.scrollDeltaX} dy=${e.scrollDeltaY} 位置=${e.scrollX},${e.scrollY} 最大=${e.maxScrollX},${e.maxScrollY} 項目=${e.fromIndex}-${e.toIndex}/${e.itemCount}"
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "変化=${e.contentChangeTypes}"
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> "変化=${e.contentChangeTypes} クラス=${e.className}"
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> "窓の変化=${e.windowChanges}"
            else -> ""
        }
        Lab.log("出来事 $type ${e.packageName} 窓=${e.windowId} $extra")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        Lab.log("キー ${KeyEvent.keyCodeToString(event.keyCode)} 動作=${event.action} 入力元=${event.source} 注入=${event.flags and KeyEvent.FLAG_FROM_SYSTEM != 0}")
        // 消費せず、前面のアプリへそのまま渡す
        return false
    }

    override fun onInterrupt() = Unit

    fun run(cmd: String) {
        Lab.log("命令 $cmd")
        when {
            cmd == "overlay_on" -> showOverlay(true)
            cmd == "overlay_off" -> showOverlay(false)
            cmd == "shot" -> shotDisplay(1, 0)
            cmd.startsWith("burst") -> shotDisplay(8, cmd.removePrefix("burst").trim().toLongOrNull() ?: 0)
            cmd == "window_shot" -> shotWindow(1, 0)
            cmd.startsWith("window_burst") -> shotWindow(8, cmd.removePrefix("window_burst").trim().toLongOrNull() ?: 0)
            cmd == "windows" -> listWindows()
            cmd == "auto_shot_on" -> autoShot(true)
            cmd == "auto_shot_off" -> autoShot(false)
            cmd == "snap" -> startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_SNAP))
            cmd == "app_overlay_on" -> MainActivity.showAppOverlay(this, true)
            cmd == "app_overlay_off" -> MainActivity.showAppOverlay(this, false)
            cmd.startsWith("mark") -> Lab.log("==== ${cmd.removePrefix("mark").trim()} ====")
            else -> Lab.log("知らない命令: $cmd（$COMMANDS）")
        }
    }

    /** 紫の重ね表示を出す・消す。触れた操作は下へ通す。 */
    fun showOverlay(show: Boolean) {
        val wm = getSystemService(WindowManager::class.java)
        overlay?.let { runCatching { wm.removeView(it) } }
        overlay = null
        if (!show) return
        val view = View(this).apply { setBackgroundColor(Lab.A11Y_COLOR) }
        wm.addView(view, WindowManager.LayoutParams(
            400, 200,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 100
            y = 600
        })
        overlay = view
        Lab.log("紫の重ね表示（ユーザー補助）を出した")
    }

    /** 画面全体を [times] 回、前の撮影の終わりから [gapMs] あけて撮り、間隔・失敗・写り方を残す（問い2・3）。 */
    private fun shotDisplay(times: Int, gapMs: Long) {
        val start = SystemClock.uptimeMillis()
        val handler = android.os.Handler(mainLooper)
        fun one(i: Int) {
            val t0 = SystemClock.uptimeMillis()
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    Lab.log("画面の撮影 $i 回目（間隔${gapMs}ms）: 成功 ${SystemClock.uptimeMillis() - t0}ms（開始から${SystemClock.uptimeMillis() - start}ms） ${bitmap?.let(Lab::describe)}")
                    bitmap?.recycle()
                    if (i < times) handler.postDelayed({ one(i + 1) }, gapMs)
                }

                override fun onFailure(errorCode: Int) {
                    Lab.log("画面の撮影 $i 回目（間隔${gapMs}ms）: 失敗 コード=$errorCode（開始から${SystemClock.uptimeMillis() - start}ms）")
                    if (i < times) handler.postDelayed({ one(i + 1) }, gapMs)
                }
            })
        }
        one(1)
    }

    /** 窓の一覧（種類・層・題名・前面か）を残す。中身は読まない。 */
    private fun listWindows() {
        windows.forEach { w ->
            Lab.log("窓 id=${w.id} 種類=${w.type} 層=${w.layer} 題名=${w.title} 前面=${w.isActive} 焦点=${w.isFocused} 範囲=${android.graphics.Rect().also(w::getBoundsInScreen)}")
        }
    }

    /**
     * 前面のアプリの窓だけを [times] 回、[gapMs] あけて撮る（問い2・3）。自分の重ね表示が写らなければ、訳の枠の下の原文を
     * いつでも見られる。1回目だけ画像を保存する。
     */
    private fun shotWindow(times: Int, gapMs: Long) {
        val target = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
        if (target == null) {
            Lab.log("窓の撮影: 前面のアプリの窓が見つからない")
            return
        }
        val start = SystemClock.uptimeMillis()
        val handler = android.os.Handler(mainLooper)
        fun one(i: Int) {
            val t0 = SystemClock.uptimeMillis()
            takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                    result.hardwareBuffer.close()
                    Lab.log("窓の撮影 $i 回目（${target.title}、間隔${gapMs}ms）: 成功 ${SystemClock.uptimeMillis() - t0}ms（開始から${SystemClock.uptimeMillis() - start}ms） ${bitmap?.let(Lab::describe)}")
                    if (i == 1) bitmap?.let { save(it, "window") }
                    bitmap?.recycle()
                    if (i < times) handler.postDelayed({ one(i + 1) }, gapMs)
                }

                override fun onFailure(errorCode: Int) {
                    Lab.log("窓の撮影 $i 回目（${target.title}、間隔${gapMs}ms）: 失敗 コード=$errorCode（開始から${SystemClock.uptimeMillis() - start}ms）")
                    if (i < times) handler.postDelayed({ one(i + 1) }, gapMs)
                }
            })
        }
        one(1)
    }

    private val autoHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val autoTick = object : Runnable {
        override fun run() {
            shotActiveForStats()
            autoHandler.postDelayed(this, 1000)
        }
    }

    /**
     * 1秒ごとに前面のアプリの窓を撮り、撮れたか・真っ黒でないか・自分の重ね表示が写ったかだけを残す（問い2をほかのアプリで確かめる）。
     * 作者の画面の中身なので、画像は保存しない。
     */
    private fun autoShot(on: Boolean) {
        autoHandler.removeCallbacks(autoTick)
        if (on) autoHandler.post(autoTick)
        Lab.log("1秒ごとの窓の撮影: ${if (on) "始めた" else "止めた"}")
    }

    private fun shotActiveForStats() {
        val target = windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION && it.isActive }
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
            ?: return Lab.log("窓の撮影（自動）: 前面のアプリの窓が無い")
        val t0 = SystemClock.uptimeMillis()
        takeScreenshotOfWindow(target.id, mainExecutor, object : TakeScreenshotCallback {
            override fun onSuccess(result: ScreenshotResult) {
                val bitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)?.copy(Bitmap.Config.ARGB_8888, false)
                result.hardwareBuffer.close()
                if (bitmap == null) return Lab.log("窓の撮影（自動、${target.title}）: 画像にできない")
                var dark = 0
                var total = 0
                var sum = 0L
                var y = 0
                while (y < bitmap.height) {
                    var x = 0
                    while (x < bitmap.width) {
                        val p = bitmap.getPixel(x, y)
                        val l = (((p shr 16) and 0xFF) * 3 + ((p shr 8) and 0xFF) * 6 + (p and 0xFF)) / 10
                        sum += l
                        if (l < 8) dark++
                        total++
                        x += 16
                    }
                    y += 16
                }
                Lab.log(
                    "窓の撮影（自動、${target.title}、層${target.layer}）: ${SystemClock.uptimeMillis() - t0}ms ${bitmap.width}x${bitmap.height} " +
                        "平均の明るさ=${sum / total} 真っ黒の割合=${dark * 100 / total}% 紫=${Lab.count(bitmap, Lab.A11Y_COLOR)} 水色=${Lab.count(bitmap, Lab.APP_COLOR)} " +
                        "緑（描画面の試験）=${countGreen(bitmap)}",
                )
                bitmap.recycle()
            }

            override fun onFailure(errorCode: Int) {
                Lab.log("窓の撮影（自動、${target.title}）: 失敗 コード=$errorCode")
            }
        })
    }

    /** 描画面の試験の緑（0,200,0）の画素の数（16画素おきに数え、256倍して返す）。 */
    private fun countGreen(bitmap: Bitmap): Int {
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                if (((p shr 16) and 0xFF) < 40 && ((p shr 8) and 0xFF) in 170..230 && (p and 0xFF) < 40) n++
                x += 16
            }
            y += 16
        }
        return n * 256
    }

    private fun save(bitmap: Bitmap, name: String) {
        val dir = getExternalFilesDir("lab") ?: return
        val file = java.io.File(dir, "$name-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        Lab.log("保存: ${file.path}")
    }

    companion object {
        const val ACTION_CMD = "app.plamotranslate.featurelab.CMD"
        const val COMMANDS = "overlay_on / overlay_off / app_overlay_on / app_overlay_off / shot / burst / window_shot / windows / mark <文> / snap（画面共有）"

        @Volatile
        var instance: LabA11yService? = null
            private set
    }
}
