package app.plamotranslate.featurelab

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log

/**
 * 機能テストの共通部品：ログ（logcat の FeatureLab と画面の表示の両方へ）と、撮った画像に自分の重ね表示が写ったかの判定。
 * 結果は PC から `adb logcat -s FeatureLab` で読む（docs/12_機能テスト.md）。
 */
object Lab {
    const val TAG = "FeatureLab"

    /** ユーザー補助の重ね表示（TYPE_ACCESSIBILITY_OVERLAY）に塗る色。画面にまず無い紫。 */
    const val A11Y_COLOR = 0xFFFF00FF.toInt()

    /** ふつうの重ね表示（TYPE_APPLICATION_OVERLAY）に塗る色。画面にまず無い水色。 */
    const val APP_COLOR = 0xFF00FFFF.toInt()

    private val lines = ArrayDeque<String>()

    fun log(message: String) {
        Log.i(TAG, message)
        synchronized(lines) {
            lines.addLast("${SystemClock.uptimeMillis() % 100_000} $message")
            while (lines.size > 200) lines.removeFirst()
        }
    }

    fun tail(n: Int): String = synchronized(lines) { lines.takeLast(n).joinToString("\n") }

    /**
     * [bitmap] の中で、紫（赤と青が強く緑が弱い）か水色（緑と青が強く赤が弱い）の画素の数（4画素おきに数え、16倍して返す）。
     * ふつうの重ね表示は不透明度0.8で背景と混ざるので、色そのものではなく色味で見る。
     */
    fun count(bitmap: Bitmap, color: Int): Int {
        val purple = color == A11Y_COLOR
        var n = 0
        var y = 0
        while (y < bitmap.height) {
            var x = 0
            while (x < bitmap.width) {
                val p = bitmap.getPixel(x, y)
                val r = (p shr 16) and 0xFF
                val g = (p shr 8) and 0xFF
                val b = p and 0xFF
                if (if (purple) r > 180 && b > 180 && g < 100 else g > 180 && b > 180 && r < 110) n++
                x += 4
            }
            y += 4
        }
        return n * 16
    }

    /** 撮った画像の、重ね表示の写り方をまとめる（紫・水色とも 400x200＝80000画素が全部写った値）。 */
    fun describe(bitmap: Bitmap): String =
        "${bitmap.width}x${bitmap.height} ユーザー補助の重ね表示の紫=${count(bitmap, A11Y_COLOR)}画素 ふつうの重ね表示の水色=${count(bitmap, APP_COLOR)}画素"
}
