package app.plamotranslate.desktop

import app.plamotranslate.desktop.pdf.PdfDocument
import app.plamotranslate.desktop.pdf.PdfFonts
import java.util.concurrent.atomic.AtomicBoolean

internal sealed interface PdfFontStatus {
    data object NotLoaded : PdfFontStatus
    data class Loading(val done: Long, val total: Long) : PdfFontStatus
    data class Ready(val fonts: PdfFonts) : PdfFontStatus
    data class Failed(val message: String) : PdfFontStatus
}

internal data class PdfDocuments(
    val documentId: Long? = null,
    val original: PdfViewVersion? = null,
    val latest: PdfViewVersion? = null,
    /** [latest] を開き直した文書で表示できるページ。 */
    val writtenPages: Set<Int> = emptySet(),
    val fonts: PdfFontStatus = PdfFontStatus.NotLoaded,
    val error: String? = null,
)

/** 画面が版を使用している間は、その文書を開いたままにする。 */
internal class PdfViewVersion(
    val number: Long,
    private val document: PdfDocument,
    private val onUnused: (PdfDocument) -> Unit,
) {
    private var leases = 0
    private var retired = false

    val pageCount: Int get() = document.pageCount

    @Synchronized
    fun acquire(): PdfViewLease? {
        if (retired && leases == 0) return null
        leases++
        return PdfViewLease(document, ::release)
    }

    internal fun retire() {
        val unused = synchronized(this) {
            if (retired) return
            retired = true
            leases == 0
        }
        if (unused) onUnused(document)
    }

    private fun release() {
        val unused = synchronized(this) {
            leases--
            retired && leases == 0
        }
        if (unused) onUnused(document)
    }
}

internal class PdfViewLease(val document: PdfDocument, private val release: () -> Unit) : AutoCloseable {
    private val closed = AtomicBoolean()

    override fun close() {
        if (closed.compareAndSet(false, true)) release()
    }
}
