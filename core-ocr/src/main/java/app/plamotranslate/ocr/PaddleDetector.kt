package app.plamotranslate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Rect
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PP-OCR の文字の検出（DB）。画像から文字の行の範囲を見つける。どの言語のモデルでも前処理と後処理は同じ。
 *
 * 長辺960px（32の倍数）、BGRで (x/255 − mean)/std。確率が0.3を超える画素のまとまりを1行とし、
 * 平均確率0.6未満は捨てる。DBの後処理の広げ幅（面積×1.5÷周長）で枠を広げる。
 * OpenCVは使わず、後処理はこのクラスで行う（APKを大きくしないため）。
 */
internal class PaddleDetector private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
) : AutoCloseable {

    fun detect(bitmap: Bitmap): List<Rect> {
        val scale = LIMIT.toFloat() / max(bitmap.width, bitmap.height)
        val nw = max(32, (bitmap.width * scale / 32).roundToInt() * 32)
        val nh = max(32, (bitmap.height * scale / 32).roundToInt() * 32)
        val resized = Bitmap.createScaledBitmap(bitmap, nw, nh, true)
        val input = PaddleTensors.of(env, resized, MEAN, STD)
        if (resized !== bitmap) resized.recycle()
        val prob = session.run(mapOf(session.inputNames.first() to input)).use { out ->
            val buffer = (out[0] as OnnxTensor).floatBuffer
            FloatArray(buffer.remaining()).also { buffer.get(it) }
        }
        input.close()

        val sx = bitmap.width.toFloat() / nw
        val sy = bitmap.height.toFloat() / nh
        val visited = BooleanArray(nw * nh)
        val queue = IntArray(nw * nh)
        val boxes = mutableListOf<Rect>()
        for (start in prob.indices) {
            if (visited[start] || prob[start] <= THRESH) continue
            // 4近傍でつながった画素のまとまりを1行とみなす
            var head = 0
            var tail = 0
            queue[tail++] = start
            visited[start] = true
            var minX = nw; var minY = nh; var maxX = 0; var maxY = 0
            var sum = 0f
            while (head < tail) {
                val p = queue[head++]
                val x = p % nw
                val y = p / nw
                sum += prob[p]
                minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (q in intArrayOf(p - 1, p + 1, p - nw, p + nw)) {
                    if (q < 0 || q >= prob.size || visited[q] || prob[q] <= THRESH) continue
                    if ((q == p - 1 && x == 0) || (q == p + 1 && x == nw - 1)) continue
                    visited[q] = true
                    queue[tail++] = q
                }
            }
            if (tail < 9 || sum / tail < BOX_THRESH) continue
            val w = maxX - minX + 1
            val h = maxY - minY + 1
            val d = w * h * UNCLIP / (2f * (w + h))
            boxes += Rect(
                ((minX - d) * sx).roundToInt().coerceAtLeast(0),
                ((minY - d) * sy).roundToInt().coerceAtLeast(0),
                ((maxX + 1 + d) * sx).roundToInt().coerceAtMost(bitmap.width),
                ((maxY + 1 + d) * sy).roundToInt().coerceAtMost(bitmap.height),
            )
        }
        return boxes.filter { it.width() >= 2 && it.height() >= 2 }
    }

    override fun close() = session.close()

    companion object {
        private const val LIMIT = 960
        private const val THRESH = 0.3f
        private const val BOX_THRESH = 0.6f
        private const val UNCLIP = 1.5f
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)

        /** [model] が読めれば読み込む。無ければ null。 */
        fun load(model: File, options: OrtSession.SessionOptions): PaddleDetector? {
            if (!model.canRead()) return null
            val env = OrtEnvironment.getEnvironment()
            return PaddleDetector(env, env.createSession(model.path, options))
        }
    }
}
