package app.plamotranslate.ocr

import android.graphics.Bitmap
import android.graphics.Rect
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognizer
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * ML Kit の認識器1つを包む [LineReader]。[keepGlyphs] なら、1文字ずつの位置も返す（中国語。縦書きの列を組み直すのに使う）。
 * ML Kit の処理は途中では止まらない。取り消されても処理が終わるまで待ってから戻り（その間も画像を読んでいるので、
 * 戻るまで呼んだ側が画像を解放しないように。[OcrEngine.recognize] の約束）、読み終えた結果は捨てる。
 */
internal class MlKitReader(
    private val source: Source,
    private val recognizer: TextRecognizer,
    private val keepGlyphs: Boolean = false,
) : LineReader {
    override val sources = setOf(source)
    override val heavy = false

    override suspend fun read(bitmap: Bitmap, wanted: Set<Source>): LineReading {
        if (source !in wanted) return LineReading(emptyMap())
        val text = withContext(NonCancellable) { recognizer.process(InputImage.fromBitmap(bitmap, 0)).await() }
        currentCoroutineContext().ensureActive()
        val lines = text.textBlocks.flatMap { block ->
            block.lines.mapNotNull { line -> line.boundingBox?.let { Candidate(line.text, Rect(it), source) } }
        }
        return LineReading(mapOf(source to lines), if (keepGlyphs) text.toGlyphs() else emptyList())
    }

    override fun close() = recognizer.close()

    private fun Text.toGlyphs(): List<Glyph> = textBlocks.flatMap { block ->
        block.lines.flatMap { line ->
            line.elements.flatMap { element ->
                element.symbols.mapNotNull { s -> s.boundingBox?.let { Glyph(s.text, Rect(it)) } }
            }
        }
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}
