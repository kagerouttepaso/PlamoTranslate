package app.plamotranslate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import org.json.JSONArray
import java.io.File
import kotlin.math.roundToInt

/**
 * PP-OCR の1行の認識（CTC）。モデルと文字の表を受け取る汎用の部品で、漫画用と韓国語用を同じクラスで作る。
 *
 * 高さ48px、幅は縦横比のまま。縦長（高さ≧幅×1.5）の行は90度左に回して横書きとして読む。
 * BGRで (x/255 − 0.5)/0.5。CTCを貪欲に復号する（0番は空白記号、文字の表の後ろに半角空白）。
 * 確信度は、採用した字の値の平均。
 */
internal class PaddleRecognizer private constructor(
    private val env: OrtEnvironment,
    private val session: OrtSession,
    private val vocab: List<String>,
) : AutoCloseable {

    /** [box] の範囲を読む。読めなければ null。 */
    fun recognize(bitmap: Bitmap, box: Rect): Pair<String, Float>? {
        var crop = Bitmap.createBitmap(bitmap, box.left, box.top, box.width(), box.height())
        if (crop.height >= crop.width * 1.5f) {
            // 縦書きの行は90度左に回して横書きとして読む
            val rotated = Bitmap.createBitmap(crop, 0, 0, crop.width, crop.height, Matrix().apply { postRotate(-90f) }, true)
            crop.recycle()
            crop = rotated
        }
        val nw = (HEIGHT * crop.width.toFloat() / crop.height).roundToInt().coerceIn(16, 3200)
        val resized = Bitmap.createScaledBitmap(crop, nw, HEIGHT, true)
        crop.recycle()
        val input = PaddleTensors.of(env, resized, MEAN, STD)
        resized.recycle()
        val (text, confidence) = session.run(mapOf(session.inputNames.first() to input)).use { out ->
            val tensor = out[0] as OnnxTensor
            val shape = tensor.info.shape   // [1, T, V]
            val steps = shape[1].toInt()
            val classes = shape[2].toInt()
            val logits = tensor.floatBuffer
            val sb = StringBuilder()
            var probSum = 0f
            var count = 0
            var prev = -1
            for (t in 0 until steps) {
                var best = 0
                var bestValue = Float.NEGATIVE_INFINITY
                for (v in 0 until classes) {
                    val value = logits.get(t * classes + v)
                    if (value > bestValue) { bestValue = value; best = v }
                }
                if (best != prev && best != 0 && best < vocab.size) {
                    sb.append(vocab[best])
                    probSum += bestValue
                    count++
                }
                prev = best
            }
            sb.toString() to (if (count > 0) probSum / count else 0f)
        }
        input.close()
        if (text.isBlank()) return null
        return text to confidence
    }

    override fun close() = session.close()

    companion object {
        private const val HEIGHT = 48
        private val MEAN = floatArrayOf(0.5f, 0.5f, 0.5f)
        private val STD = floatArrayOf(0.5f, 0.5f, 0.5f)

        /**
         * [model] と文字の表 [dict] が読めれば読み込む。無ければ null。
         * 文字の表は1行1字のテキスト（`.txt`）か、文字の配列の JSON（`.json`）。
         */
        fun load(model: File, dict: File, options: OrtSession.SessionOptions): PaddleRecognizer? {
            if (!model.canRead() || !dict.canRead()) return null
            val chars = if (dict.extension == "json") {
                val array = JSONArray(dict.readText(Charsets.UTF_8))
                List(array.length()) { array.getString(it) }
            } else {
                dict.readLines(Charsets.UTF_8)
            }
            val env = OrtEnvironment.getEnvironment()
            return PaddleRecognizer(env, env.createSession(model.path, options), listOf("<blank>") + chars + listOf(" "))
        }
    }
}
