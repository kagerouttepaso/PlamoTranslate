package app.plamotranslate.ocr

import ai.onnxruntime.OrtSession
import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.coroutineContext

/**
 * PP-OCR の読み手。検出（[PaddleDetector]）を1回動かし、見つけた行ごとに、求められた認識器（[PaddleRecognizer]）を当てる。
 * 1行読むごとに取り消されていないかを確かめ、取り消されたらそこでやめる。
 */
internal class PaddleReader(
    private val detector: PaddleDetector,
    private val recognizers: Map<Source, PaddleRecognizer>,
) : LineReader {
    override val sources: Set<Source> = recognizers.keys
    override val heavy = true

    override suspend fun read(bitmap: Bitmap, wanted: Set<Source>): LineReading = withContext(Dispatchers.Default) {
        val use = recognizers.filterKeys { it in wanted }
        if (use.isEmpty()) return@withContext LineReading(emptyMap())
        val boxes = detector.detect(bitmap)
        val lines = use.keys.associateWith { mutableListOf<Candidate>() }
        for (box in boxes) {
            for ((source, recognizer) in use) {
                coroutineContext.ensureActive()
                val (text, confidence) = recognizer.recognize(bitmap, box) ?: continue
                lines.getValue(source) += Candidate(text, box, source, confidence)
            }
        }
        LineReading(lines)
    }

    override fun close() {
        detector.close()
        recognizers.values.forEach { it.close() }
    }

    companion object {
        /**
         * [dir]（端末の `files/models/ocr/`）にあるモデルから作る。ファイルの並びは [PaddleModels]。
         * 読めない認識器だけを外し（壊れたモデルが、ほかの認識器を道連れにしない）、検出か認識器が1つも無ければ null（ML Kit だけで動く）。
         */
        fun load(dir: File, threads: Int = 4): PaddleReader? {
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            val detector = runCatching { PaddleDetector.load(File(dir, PaddleModels.DETECTOR), options) }.getOrNull() ?: return null
            val recognizers = PaddleModels.recognizers.mapNotNull { (source, files) ->
                runCatching { PaddleRecognizer.load(File(dir, files.model), File(dir, files.dict), options) }.getOrNull()
                    ?.let { source to it }
            }.toMap()
            if (recognizers.isEmpty()) {
                detector.close()
                return null
            }
            return PaddleReader(detector, recognizers)
        }
    }
}
