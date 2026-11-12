package app.plamotranslate.featurelab

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager

/**
 * 機能テストの画面共有（問い2。docs/資料/端末の能力.md）。確認画面で「画面全体」か「1つのアプリ」かを選んで始め、
 * 届いた画面の数（1秒ごと）と、撮った1枚に自分の重ね表示（紫・水色）が写ったかを残す。
 * 1つのアプリを選んだとき、そのアプリを離れたら画面が届き続けるか（onCapturedContentVisibilityChanged）も残す。
 */
class CaptureService : Service() {
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private val thread = HandlerThread("lab-capture").apply { start() }
    private val handler = Handler(thread.looper)
    private var latest: Image? = null
    private var frames = 0
    private var lastFrameAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            Lab.log("画面共有: この1秒に届いた画面 ${frames}枚（最後に届いてから${SystemClock.uptimeMillis() - lastFrameAt}ms）")
            frames = 0
            handler.postDelayed(this, 1000)
        }
    }

    override fun onBind(intent: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> runCatching { start(intent) }.onFailure { Lab.log("画面共有: 始められない $it") }
            ACTION_SNAP -> handler.post(::snap)
            ACTION_STOP -> stopSelf()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        val channel = NotificationChannel("lab", "機能テスト", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        val notification = Notification.Builder(this, "lab").setContentTitle("機能テスト：画面共有中").setSmallIcon(android.R.drawable.ic_menu_camera).build()
        startForeground(1, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(code, it) }
        if (mp == null) {
            Lab.log("画面共有: 許可の結果から画面共有を作れない（data=${data != null}）")
            return
        }
        projection = mp
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = Lab.log("画面共有: 止まった")
            override fun onCapturedContentResize(width: Int, height: Int) = Lab.log("画面共有: 写す範囲の大きさ ${width}x$height")
            override fun onCapturedContentVisibilityChanged(isVisible: Boolean) = Lab.log("画面共有: 写す対象が見えている=$isVisible")
        }, handler)
        val bounds = getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        val r = ImageReader.newInstance(bounds.width(), bounds.height(), PixelFormat.RGBA_8888, 2)
        r.setOnImageAvailableListener({ ir ->
            val image = ir.acquireLatestImage() ?: return@setOnImageAvailableListener
            latest?.close()
            latest = image
            frames++
            lastFrameAt = SystemClock.uptimeMillis()
        }, handler)
        reader = r
        display = mp.createVirtualDisplay(
            "lab", bounds.width(), bounds.height(), resources.displayMetrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, handler,
        )
        Lab.log("画面共有: 始めた ${bounds.width()}x${bounds.height()}")
        handler.postDelayed(tick, 1000)
    }

    /** 最新の1枚を画像にし、重ね表示の写り方を残して保存する。 */
    private fun snap() {
        val image = latest ?: run {
            Lab.log("画面共有の撮影: まだ画面が届いていない")
            return
        }
        val plane = image.planes[0]
        val padded = plane.rowStride / plane.pixelStride
        val bitmap = Bitmap.createBitmap(padded, image.height, Bitmap.Config.ARGB_8888)
        bitmap.copyPixelsFromBuffer(plane.buffer.rewind())
        val cropped = Bitmap.createBitmap(bitmap, 0, 0, image.width, image.height)
        Lab.log("画面共有の撮影: ${Lab.describe(cropped)}（最後に届いてから${SystemClock.uptimeMillis() - lastFrameAt}ms）")
        getExternalFilesDir("lab")?.let { dir ->
            val file = java.io.File(dir, "projection-${System.currentTimeMillis()}.png")
            file.outputStream().use { cropped.compress(Bitmap.CompressFormat.PNG, 100, it) }
            Lab.log("保存: ${file.path}")
        }
        bitmap.recycle()
        cropped.recycle()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        display?.release()
        projection?.stop()
        latest?.close()
        reader?.close()
        thread.quitSafely()
        Lab.log("画面共有: 終えた")
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "start"
        const val ACTION_SNAP = "snap"
        const val ACTION_STOP = "stop"
        const val EXTRA_CODE = "code"
        const val EXTRA_DATA = "data"
    }
}
