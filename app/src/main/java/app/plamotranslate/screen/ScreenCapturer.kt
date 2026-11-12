package app.plamotranslate.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.WindowManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * MediaProjectionの画面を常にImageReaderで受け、最新の1枚だけを持っておく（docs/05_画面翻訳.md）。
 * Android 14以降は1つの許可で仮想ディスプレイを1回しか作れないため、サービスの間ずっと使い続ける。
 *
 * 画面を回したら、仮想ディスプレイと受け取る画像の大きさを今の画面に合わせる（[matchScreenSize]）。
 * 合わせないと、横長の画面が始めたときの縦長の枠に縮めて映り（上下に余白）、撮った画像の座標と画面の座標がずれて、
 * 訳の枠が違う場所に出る（2026-11-09、作者の指摘「横画面になっているときレンダリングがバグる」）。
 */
class ScreenCapturer(context: Context, projection: MediaProjection) {
    private val windowManager = context.getSystemService(WindowManager::class.java)
    private val dpi = context.resources.displayMetrics.densityDpi
    private val thread = HandlerThread("ScreenCapturer").apply { start() }
    private val handler = Handler(thread.looper)
    private val display: VirtualDisplay

    // 作るときを除き、handler のスレッドだけが触る
    private var reader: ImageReader
    private var latest: Image? = null
    private var latestAt = 0L

    init {
        val bounds = windowManager.maximumWindowMetrics.bounds
        reader = newReader(bounds.width(), bounds.height())
        display = projection.createVirtualDisplay(
            "PlamoTranslate", bounds.width(), bounds.height(), dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.surface, null, handler,
        ) ?: error("仮想ディスプレイを作れませんでした")
    }

    private fun newReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3).apply {
            setOnImageAvailableListener({ r ->
                // 大きさを変える前の受け取り口から遅れて届いた通知は捨てる（閉じた後に読むと例外になる）
                if (r !== reader) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                latest?.close()
                latest = image
                latestAt = SystemClock.uptimeMillis()
            }, handler)
        }

    /**
     * [since]（uptimeMillis）より後に届いた画面を1枚返す。自分の表示を消してから呼ぶと、消えた後の画面が取れる。
     * 画面が止まっていて新しい1枚が来ない場合は、待ち時間の上限で手元の最新を使う。
     * 画面を回した後は、大きさを合わせてから届いた1枚を待つ。
     */
    suspend fun capture(since: Long, timeoutMillis: Long = 500): Bitmap? {
        val resizedAt = matchScreenSize()
        val after = maxOf(since, resizedAt ?: 0L)
        val deadline = SystemClock.uptimeMillis() + if (resizedAt != null) maxOf(timeoutMillis, RESIZE_TIMEOUT_MS) else timeoutMillis
        while (true) {
            val bitmap = onHandler {
                val image = latest ?: return@onHandler null
                if (latestAt <= after && SystemClock.uptimeMillis() < deadline) null else image.toBitmap()
            }
            if (bitmap != null) return bitmap
            if (SystemClock.uptimeMillis() >= deadline && onHandler { latest == null }) return null
            delay(16)
        }
    }

    /**
     * [since] より後の画面を1枚撮り、続けて [intervalMillis] ごとに新しく届いた画面を撮って、最大 [count] 枚を返す
     * （動く画面のモード。[FrameStack]）。間隔の間に新しい画面が届かなければ、画面が止まっているので、そこで打ち切る
     * （止まった画面を何枚撮っても同じで、待つだけ遅くなる）。撮れなければ空。
     */
    suspend fun captureFrames(since: Long, count: Int, intervalMillis: Long): List<Bitmap> {
        val first = capture(since) ?: return emptyList()
        val frames = mutableListOf(first)
        var takenAt = onHandler { latestAt }
        while (frames.size < count) {
            delay(intervalMillis)
            val next = onHandler {
                val image = latest
                if (image == null || latestAt <= takenAt) return@onHandler null
                takenAt = latestAt
                image.toBitmap()
            } ?: break
            // 撮っている間に画面を回したら、大きさの違うコマは重ねられないので、そこまでにする
            if (next.width != first.width || next.height != first.height) {
                next.recycle()
                break
            }
            frames += next
        }
        return frames
    }

    /**
     * [after]（uptimeMillis）より後に新しい画面が届いていれば、それを格子に縮めて（[ScreenChange]）届いた時刻と返す。
     * 届いていなければ null（画面は変わっていない）。画像には変えずに受け取った画面から直接読むので軽い（自動で訳すときの見張り）。
     */
    internal suspend fun latestThumb(after: Long): Pair<Thumb, Long>? = onHandler {
        val image = latest ?: return@onHandler null
        if (latestAt <= after) return@onHandler null
        val plane = image.planes[0]
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        // RGBA_8888 の並び（赤・緑・青・アルファ）
        val thumb = ScreenChange.thumb(image.width, image.height) { x, y ->
            val i = y * rowStride + x * pixelStride
            val r = buffer.get(i).toInt() and 0xFF
            val g = buffer.get(i + 1).toInt() and 0xFF
            val b = buffer.get(i + 2).toInt() and 0xFF
            (r * 3 + g * 6 + b) / 10
        }
        thumb to latestAt
    }

    /**
     * 仮想ディスプレイの大きさが今の画面（回した後の向き）と違えば合わせ、その時刻を返す。同じなら null。
     * 仮想ディスプレイは作り直せないので、大きさだけ変え、受け取り口（ImageReader）を新しい大きさのものに差し替える。
     * 前の大きさの画像は座標が合わないので捨てる。
     */
    private suspend fun matchScreenSize(): Long? {
        val bounds = windowManager.maximumWindowMetrics.bounds
        val width = bounds.width()
        val height = bounds.height()
        return onHandler {
            if (width == reader.width && height == reader.height) return@onHandler null
            val old = reader
            reader = newReader(width, height)
            display.resize(width, height, dpi)
            display.surface = reader.surface
            latest?.close()
            latest = null
            old.close()
            SystemClock.uptimeMillis()
        }
    }

    fun release() {
        handler.post {
            display.release()
            latest?.close()
            latest = null
            reader.close()
            thread.quitSafely()
        }
    }

    private suspend fun <T> onHandler(block: () -> T): T = suspendCancellableCoroutine { cont ->
        handler.post { cont.resume(block()) }
    }

    private companion object {
        /** 大きさを変えた後、新しい大きさの最初の1枚を待つ上限。 */
        const val RESIZE_TIMEOUT_MS = 1500L
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes[0]
        // 行の末尾に詰め物があるので、行幅で作ってから切り出す
        val rowWidth = plane.rowStride / plane.pixelStride
        val padded = Bitmap.createBitmap(rowWidth, height, Bitmap.Config.ARGB_8888)
        padded.copyPixelsFromBuffer(plane.buffer.rewind())
        return if (rowWidth == width) padded else Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }
}
