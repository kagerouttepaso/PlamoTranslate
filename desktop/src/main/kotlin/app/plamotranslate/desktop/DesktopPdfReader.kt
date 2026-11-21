package app.plamotranslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfGlyph
import app.plamotranslate.pipeline.pdf.PdfLayout
import app.plamotranslate.pipeline.pdf.PdfRead
import app.plamotranslate.pipeline.pdf.PdfRect
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.awt.image.BufferedImage
import kotlin.math.roundToInt
import kotlin.math.sqrt

internal data class PdfPageImage(val bitmap: ImageBitmap, val width: Float, val height: Float)

internal interface PdfReader {
    val events: Flow<PdfEvent<PdfPageImage>>
    fun read(request: PdfRead)
}

internal class DesktopPdfReader(scope: CoroutineScope) : PdfReader {
    private val requests = Channel<PdfRead>(Channel.UNLIMITED)
    private val results = Channel<PdfEvent<PdfPageImage>>(Channel.UNLIMITED)
    override val events = results.receiveAsFlow()

    init {
        scope.launch(Dispatchers.IO) {
            var handle = 0L
            var document = 0L
            try {
                for (request in requests) {
                    val result = try {
                        when (request) {
                            is PdfRead.Open -> {
                                if (handle != 0L) PdfNative.close(handle)
                                handle = 0L
                                document = request.document
                                handle = PdfNative.open(request.path)
                                PdfEvent.Opened(request, if (handle == 0L) 0 else PdfNative.pageCount(handle))
                            }
                            is PdfRead.Render -> PdfEvent.Rendered(request, if (request.document == document && handle != 0L)
                                renderPdf(handle, request.page, request.zoom) else null)
                            is PdfRead.Extract -> PdfEvent.Extracted(request, if (request.document == document && handle != 0L) {
                                val size = pdfSize(handle, request.page)
                                PdfLayout.paragraphs(readPdfGlyphs(handle, request.page), size[0] / size[1])
                            } else null)
                        }
                    } catch (cancelled: CancellationException) {
                        throw cancelled
                    } catch (_: Exception) {
                        failure(request)
                    } catch (_: LinkageError) {
                        failure(request)
                    }
                    results.send(result)
                }
            } finally {
                if (handle != 0L) PdfNative.close(handle)
                requests.cancel()
                results.close()
            }
        }
    }

    override fun read(request: PdfRead) {
        requests.trySend(request)
    }

    private fun failure(request: PdfRead): PdfEvent<PdfPageImage> = when (request) {
        is PdfRead.Open -> PdfEvent.Opened(request, 0)
        is PdfRead.Render -> PdfEvent.Rendered(request, null)
        is PdfRead.Extract -> PdfEvent.Extracted(request, null)
    }
}

internal fun pdfSize(handle: Long, page: Int): FloatArray = checkNotNull(PdfNative.pageSize(handle, page)).also { size ->
    check(size.all { it.isFinite() && it > 0 })
}

internal fun readPdfGlyphs(handle: Long, page: Int): List<PdfGlyph> {
    val data = checkNotNull(PdfNative.glyphs(handle, page))
    var space = false
    return buildList {
        for (index in data.indices step 5) {
            val code = data[index].toInt()
            if (!Character.isValidCodePoint(code) || code in 0xD800..0xDFFF) continue
            val text = String(Character.toChars(code))
            val box = PdfRect(data[index + 1], data[index + 2], data[index + 3], data[index + 4])
            if (text.isBlank()) {
                space = true
            } else if (box.left >= 0 && box.top >= 0 && box.right <= 1 && box.bottom <= 1) {
                add(PdfGlyph(text, box, spaceBefore = space))
                space = false
            }
        }
    }
}

internal fun renderPdf(handle: Long, page: Int, zoom: Int): PdfPageImage {
    val (width, height) = pdfSize(handle, page)
    val scale = minOf(zoom / 100f * 1.5f, 4096f / maxOf(width, height), sqrt(5_000_000f / width / height))
    val pixelsWide = (width * scale).roundToInt().coerceIn(1, 4096)
    val pixelsHigh = (height * scale).roundToInt().coerceIn(1, 4096)
    val pixels = checkNotNull(PdfNative.render(handle, page, pixelsWide, pixelsHigh))
    val bitmap = BufferedImage(pixelsWide, pixelsHigh, BufferedImage.TYPE_INT_ARGB)
    bitmap.setRGB(0, 0, pixelsWide, pixelsHigh, pixels, 0, pixelsWide)
    return PdfPageImage(bitmap.toComposeImageBitmap(), width, height)
}
