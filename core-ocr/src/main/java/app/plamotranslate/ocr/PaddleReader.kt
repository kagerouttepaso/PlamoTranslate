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

    override suspend fun read(bitmap: Bitmap, wanted: Set<Source>): LineReading = withContext(Dispatchers.Default) {
        val use = recognizers.filterKeys { it in wanted }
        if (use.isEmpty()) return@withContext LineReading(emptyMap())
        val boxes = detector.detect(bitmap)
        val lines = use.keys.associateWith { mutableListOf<Candidate>() }
        for (box in boxes) {
            for ((source, recognizer) in use) {
                coroutineContext.ensureActive()
                val (text, confidence) = recognizer.recognize(bitmap, box) ?: continue
                val line = Candidate(text, box, source, confidence)
                if (source.accepts(line)) lines.getValue(source) += line
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
         * [dir]（端末の `files/models/ocr/`）にあるモデルから作る。検出は漫画用の `manga/det.onnx` を使う。
         * 検出か認識器が1つも無ければ null（ML Kit だけで動く）。置き場は docs/手順/開発環境.md。
         */
        fun load(dir: File, threads: Int = 4): PaddleReader? {
            val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
            val detector = PaddleDetector.load(File(dir, "manga/det.onnx"), options) ?: return null
            val recognizers = buildMap {
                PaddleRecognizer.load(File(dir, "manga/rec.onnx"), File(dir, "manga/dict.txt"), options)
                    ?.let { put(Source.PADDLE_ZH_JA, it) }
                PaddleRecognizer.load(File(dir, "korean/rec.onnx"), File(dir, "korean/dict.json"), options)
                    ?.let { put(Source.PADDLE_KO, it) }
            }
            if (recognizers.isEmpty()) {
                detector.close()
                return null
            }
            return PaddleReader(detector, recognizers)
        }
    }
}
