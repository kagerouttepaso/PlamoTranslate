package app.plamotranslate.featurelab

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * 機能テストの画面。許可の設定を開き、重ね表示を出し入れし、画面共有を始め、撮影を試す。
 * 同じ操作は PC からも `adb shell am broadcast -a app.plamotranslate.featurelab.CMD --es cmd <命令>` でできる。
 */
class MainActivity : Activity() {
    private lateinit var log: TextView
    private val refresh = object : Runnable {
        override fun run() {
            log.text = Lab.tail(40)
            log.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 96, 24, 24) }
        fun button(label: String, action: () -> Unit) = column.addView(Button(this).apply { text = label; setOnClickListener { action() } })
        button("ユーザー補助の設定を開く") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        button("重ね表示の許可を開く") {
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
        }
        button("紫と水色の重ね表示を出す") { LabA11yService.instance?.showOverlay(true); showAppOverlay(this, true) }
        button("重ね表示を消す") { LabA11yService.instance?.showOverlay(false); showAppOverlay(this, false) }
        button("画面共有を始める（確認画面で全体か1アプリかを選ぶ）") {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            @Suppress("DEPRECATION")
            startActivityForResult(mpm.createScreenCaptureIntent(), REQUEST_CAPTURE)
        }
        button("画面共有で1枚撮る") { LabA11yService.instance?.run("snap") ?: startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_SNAP)) }
        button("画面共有を止める") { startService(Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_STOP)) }
        button("ユーザー補助で画面を撮る") { LabA11yService.instance?.run("shot") }
        button("ユーザー補助で前面の窓を撮る") { LabA11yService.instance?.run("window_shot") }
        log = TextView(this).apply { textSize = 11f }
        column.addView(log)
        setContentView(ScrollView(this).apply { addView(column) })
        intent.getStringExtra("exit")?.let { ExitLab.run(this, it) }
        // 全画面の触れない重ね表示を出すと、下のアプリへの操作が届くか（訳の枠を1つの窓にまとめる前に確かめる）
        when (intent.getStringExtra("overlay")) {
            "full_on" -> { showFullOverlay(this, true); finish() }
            "full_off" -> { showFullOverlay(this, false); finish() }
        }
        if (checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 0)
        }
    }

    override fun onResume() {
        super.onResume()
        log.post(refresh)
    }

    override fun onPause() {
        log.removeCallbacks(refresh)
        super.onPause()
    }

    @Deprecated("Activity の結果の受け取り")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_CAPTURE) return
        if (resultCode != RESULT_OK || data == null) {
            Lab.log("画面共有: 許可されなかった")
            return
        }
        startForegroundService(
            Intent(this, CaptureService::class.java).setAction(CaptureService.ACTION_START)
                .putExtra(CaptureService.EXTRA_CODE, resultCode).putExtra(CaptureService.EXTRA_DATA, data),
        )
    }

    companion object {
        private const val REQUEST_CAPTURE = 1
        private var appOverlay: View? = null
        private var fullOverlay: View? = null

        /**
         * 画面全体を覆う、触れない（FLAG_NOT_TOUCHABLE）ふつうの重ね表示を出す・消す。不透明度は通り抜けの上限
         * （maximumObscuringOpacityForTouch）に合わせ、中に水色の四角を1つ置く。下のアプリをスクロールできるかを見る。
         */
        fun showFullOverlay(context: Context, show: Boolean) {
            val app = context.applicationContext
            val wm = app.getSystemService(WindowManager::class.java)
            fullOverlay?.let { runCatching { wm.removeView(it) } }
            fullOverlay = null
            if (!show) {
                Lab.log("全画面の重ね表示: 消した")
                return
            }
            if (!Settings.canDrawOverlays(app)) {
                Lab.log("全画面の重ね表示: 許可がない")
                return
            }
            val limit = app.getSystemService(android.hardware.input.InputManager::class.java).maximumObscuringOpacityForTouch
            val bounds = wm.currentWindowMetrics.bounds
            val root = android.widget.FrameLayout(app).apply {
                addView(View(app).apply { setBackgroundColor(Lab.APP_COLOR) }, android.widget.FrameLayout.LayoutParams(400, 200).apply {
                    leftMargin = 100
                    topMargin = 900
                })
            }
            wm.addView(root, WindowManager.LayoutParams(
                bounds.width(), bounds.height(),
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                fitInsetsTypes = 0
                layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                alpha = limit
            })
            fullOverlay = root
            Lab.log("全画面の重ね表示: 出した（${bounds.width()}x${bounds.height()}、不透明度 $limit）")
        }

        /** 水色のふつうの重ね表示（TYPE_APPLICATION_OVERLAY）を出す・消す。触れた操作は下へ通す。 */
        fun showAppOverlay(context: Context, show: Boolean) {
            val wm = context.applicationContext.getSystemService(WindowManager::class.java)
            appOverlay?.let { runCatching { wm.removeView(it) } }
            appOverlay = null
            if (!show) return
            if (!Settings.canDrawOverlays(context)) {
                Lab.log("ふつうの重ね表示: 許可がない")
                return
            }
            val view = View(context.applicationContext).apply { setBackgroundColor(Lab.APP_COLOR) }
            wm.addView(view, WindowManager.LayoutParams(
                400, 200,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = 100
                y = 900
                alpha = 0.8f
            })
            appOverlay = view
            Lab.log("水色の重ね表示（ふつう）を出した")
        }
    }
}
