package app.plamotranslate.ocr

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.graphics.Bitmap
import java.nio.FloatBuffer

/** PP-OCR のモデルへの入力。ARGBのビットマップを、BGRの順・チャンネル優先（NCHW）で正規化する。 */
internal object PaddleTensors {
    fun of(env: OrtEnvironment, bitmap: Bitmap, mean: FloatArray, std: FloatArray): OnnxTensor {
        val w = bitmap.width
        val h = bitmap.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (i in 0 until plane) {
            val c = pixels[i]
            val r = (c shr 16 and 0xFF) / 255f
            val g = (c shr 8 and 0xFF) / 255f
            val b = (c and 0xFF) / 255f
            data[i] = (b - mean[0]) / std[0]
            data[plane + i] = (g - mean[1]) / std[1]
            data[2 * plane + i] = (r - mean[2]) / std[2]
        }
        return OnnxTensor.createTensor(env, FloatBuffer.wrap(data), longArrayOf(1, 3, h.toLong(), w.toLong()))
    }
}
