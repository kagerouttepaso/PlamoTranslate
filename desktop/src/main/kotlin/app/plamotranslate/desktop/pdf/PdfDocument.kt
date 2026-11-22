package app.plamotranslate.desktop.pdf

import app.plamotranslate.desktop.PdfNative
import app.plamotranslate.pipeline.pdf.PdfBackground
import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfColor
import app.plamotranslate.pipeline.pdf.PdfPageSize
import app.plamotranslate.pipeline.pdf.PdfPageText
import app.plamotranslate.pipeline.pdf.PdfParagraph
import app.plamotranslate.pipeline.pdf.PdfRect
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil
import kotlin.math.sqrt

sealed interface PdfDestination {
    data class Page(val index: Int, val x: Double?, val y: Double?, val zoom: Double?) : PdfDestination
    data class Uri(val uri: String) : PdfDestination
}

data class PdfLink(val index: Int, val bounds: PdfBounds, val destination: PdfDestination?, val quads: List<PdfBounds>) {
    val hitAreas: List<PdfBounds> get() = quads.ifEmpty { listOf(bounds) }
}

data class PdfBookmark(val title: String, val destination: PdfDestination?, val children: List<PdfBookmark>)
data class PdfSearchHit(val start: Int, val length: Int, val boxes: List<PdfBounds>)
data class PdfSelection(val text: String, val boxes: List<PdfBounds>)
data class PdfPoint(val x: Double, val y: Double)

/** PDF のポイント座標と、画面左上を原点とする 0〜1 の座標を相互変換する。 */
class PdfViewport internal constructor(private val origin: PdfPoint, private val right: PdfPoint, private val bottom: PdfPoint) {
    private val xx = right.x - origin.x
    private val xy = right.y - origin.y
    private val yx = bottom.x - origin.x
    private val yy = bottom.y - origin.y
    private val determinant = xx * yy - yx * xy

    fun toPage(x: Double, y: Double) = PdfPoint(origin.x + x * xx + y * yx, origin.y + x * xy + y * yy)

    fun toView(x: Double, y: Double): PdfPoint {
        val dx = x - origin.x
        val dy = y - origin.y
        return PdfPoint((dx * yy - dy * yx) / determinant, (dy * xx - dx * xy) / determinant)
    }

    fun toView(bounds: PdfBounds): PdfRect {
        val corners = listOf(
            toView(bounds.left, bounds.bottom), toView(bounds.left, bounds.top),
            toView(bounds.right, bounds.bottom), toView(bounds.right, bounds.top),
        )
        return PdfRect(corners.minOf { it.x }.toFloat(), corners.minOf { it.y }.toFloat(),
            corners.maxOf { it.x }.toFloat(), corners.maxOf { it.y }.toFloat())
    }
}

/**
 * PDFium 文書。ページと文字の番号は 0 始まり。読み取り結果と編集は PDF のポイント座標を使う。
 * 画面表示用は元ファイルやスナップショットを開き、編集用は同じ原文のバイト列から別の文書を開く。
 */
class PdfDocument private constructor() {
    private val dispatcher = Executors.newSingleThreadExecutor { task ->
        Thread(task, "mockingword-pdf-document").apply { isDaemon = true }
    }.asCoroutineDispatcher()
    private val closing = AtomicBoolean()
    private var handle = 0L
    private var writeFailed = false
    private var fontSet: PdfFonts? = null
    private val writtenPages = mutableSetOf<Int>()
    private val usedRegular = linkedSetOf<Int>()
    private val usedBold = linkedSetOf<Int>()

    var pageCount: Int = 0
        private set
    var encrypted: Boolean = false
        private set

    private suspend fun <T> call(operation: (Long) -> T): T {
        check(!closing.get()) { "PDF は閉じています" }
        return withContext(dispatcher) {
            check(handle != 0L && !closing.get()) { "PDF は閉じています" }
            check(!writeFailed) { "書き込みに失敗した PDF です" }
            operation(handle)
        }
    }

    suspend fun close() {
        if (!closing.compareAndSet(false, true)) return
        try {
            withContext(NonCancellable + dispatcher) {
                if (handle != 0L) PdfNative.close(handle)
                handle = 0L
            }
        } finally {
            dispatcher.close()
        }
    }

    suspend fun pageSize(page: Int): PdfPageSize = call { size(it, page) }

    suspend fun pageText(page: Int): PdfPageText = call {
        PdfPageText(size(it, page), packet(PdfEditingNative.characters(it, page)).glyphs())
    }

    suspend fun render(page: Int, width: Int, height: Int): IntArray = call {
        checkNotNull(PdfNative.render(it, page, width, height)) { "PDF のページを描画できません" }
    }

    suspend fun viewport(page: Int): PdfViewport = call {
        val points = checkNotNull(PdfEditingNative.viewport(it, page))
        PdfViewport(PdfPoint(points[0], points[1]), PdfPoint(points[2], points[3]), PdfPoint(points[4], points[5]))
    }

    suspend fun links(page: Int): List<PdfLink> = call { packet(PdfEditingNative.links(it, page)).links() }

    suspend fun outline(): List<PdfBookmark> = call { packet(PdfEditingNative.outline(it)).outline() }

    suspend fun search(page: Int, query: String, matchCase: Boolean = false, wholeWord: Boolean = false): List<PdfSearchHit> = call {
        if (query.isBlank()) return@call emptyList()
        require('\u0000' !in query)
        val flags = (if (matchCase) 1 else 0) or (if (wholeWord) 2 else 0)
        val result = packet(PdfEditingNative.search(it, page, query, flags))
        List(result.int()) { PdfSearchHit(result.int(), result.int(), result.boxes()) }
    }

    suspend fun characterAt(page: Int, x: Double, y: Double, tolerance: Double = 2.0): Int? = call {
        require(x.isFinite() && y.isFinite() && tolerance.isFinite() && tolerance >= 0)
        PdfEditingNative.characterAt(it, page, x, y, tolerance).takeIf { index -> index >= 0 }
    }

    suspend fun selection(page: Int, start: Int, length: Int): PdfSelection = call {
        val result = packet(PdfEditingNative.selection(it, page, start, length))
        PdfSelection(result.string(Charsets.UTF_16LE), result.boxes())
    }

    /** 原文を覆う矩形と途中表示に使う背景色を、原文の画素から求める。 */
    suspend fun backgroundColors(page: Int, paragraphs: List<PdfParagraph>): Map<PdfParagraph, PdfColor> = call {
        backgrounds(it, page, paragraphs)
    }

    /**
     * 原文から組み立てたページ全体の段落と確定訳を渡す。書いたページは同じ文書で上書きできない。
     * [backgrounds] を渡すと、途中表示と同じ背景色を使う。
     */
    suspend fun writePage(
        page: Int,
        paragraphs: List<PdfParagraph>,
        translations: Map<String, String>,
        fonts: PdfFonts,
        backgrounds: Map<PdfParagraph, PdfColor> = emptyMap(),
    ): PdfWriteResult = call { document ->
        check(page !in writtenPages) { "このページは書き込み済みです" }
        val locations = packet(PdfEditingNative.links(document, page)).links()
        val edit = pageEdit(paragraphs, translations, fonts.measure, locations) { masked ->
            val missing = masked.filterNot(backgrounds::containsKey)
            backgrounds + backgrounds(document, page, missing)
        } ?: return@call PdfWriteResult(0, 0, 0)

        val existing = fontSet
        check(existing == null || existing === fonts) { "同じ文書には同じ PdfFonts を渡してください" }
        if (existing == null) {
            check(PdfEditingNative.embedFonts(document, fonts.regular, fonts.bold)) { "PDF にフォントを読み込めません" }
            fontSet = fonts
        }
        val code = edit.apply(document, page)
        if (code < 0) writeFailed = true
        check(code == 1) { "PDF にページを書き込めません" }
        writtenPages += page
        for (item in edit.content) {
            if (item is PdfContent.Text) {
                val used = if (item.bold) usedBold else usedRegular
                item.text.codePoints().forEach { used.add(it) }
            }
        }
        edit.result
    }

    /** その時点の全文書を保存する。表示と保存で同じスナップショットを使える。 */
    suspend fun snapshot(): PdfSnapshot = call {
        val bytes = checkNotNull(PdfEditingNative.save(it)) { "PDF を保存できません" }
        PdfSnapshot(bytes, encrypted, fontSet, usedRegular.toIntArray(), usedBold.toIntArray())
    }

    companion object {
        suspend fun open(path: Path): PdfDocument = open { PdfNative.open(path.toAbsolutePath().toString()) }

        suspend fun open(bytes: ByteArray): PdfDocument = open { PdfEditingNative.openBytes(bytes) }

        private suspend fun open(load: () -> Long): PdfDocument {
            val document = PdfDocument()
            try {
                withContext(document.dispatcher) {
                    val handle = load()
                    check(handle != 0L) { "PDF を開けません" }
                    document.handle = handle
                    document.pageCount = PdfNative.pageCount(handle)
                    document.encrypted = PdfEditingNative.isEncrypted(handle)
                }
                return document
            } catch (failure: Throwable) {
                document.close()
                throw failure
            }
        }
    }
}

private fun packet(bytes: ByteArray?) = PdfPacket(checkNotNull(bytes) { "PDF のページ情報を読み取れません" })

private fun size(handle: Long, page: Int): PdfPageSize {
    val (width, height) = checkNotNull(PdfNative.pageSize(handle, page)) { "PDF のページ寸法を取得できません" }
    return PdfPageSize(width.toDouble(), height.toDouble())
}

private fun backgrounds(handle: Long, page: Int, paragraphs: List<PdfParagraph>): Map<PdfParagraph, PdfColor> {
    if (paragraphs.isEmpty()) return emptyMap()
    val size = size(handle, page)
    val scale = minOf(1.0, 4096 / maxOf(size.width, size.height), sqrt(5_000_000 / size.width / size.height))
    val width = ceil(size.width * scale).toInt().coerceIn(1, 4096)
    val height = ceil(size.height * scale).toInt().coerceIn(1, 4096)
    val pixels = checkNotNull(PdfNative.render(handle, page, width, height)) { "PDF の背景色を取得できません" }
    val background = PdfBackground(pixels, width, height, size)
    return paragraphs.associateWith { background.color(it.translationBounds ?: it.bounds, it.color) }
}
