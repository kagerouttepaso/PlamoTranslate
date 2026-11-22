package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfTitles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import java.io.ByteArrayOutputStream
import java.util.Collections
import java.util.IdentityHashMap

data class PdfFinished(val bytes: ByteArray, val translatedBookmarks: Int, val encrypted: Boolean)

/** 画面に表示できるバイト列と、その時点までに書いた字形の記録。 */
class PdfSnapshot internal constructor(
    val bytes: ByteArray,
    val encrypted: Boolean,
    private val fonts: PdfFonts?,
    private val regularCodePoints: IntArray,
    private val boldCodePoints: IntArray,
) {
    /** スナップショットを書き換えずに、保存用の PDF を仕上げる。 */
    suspend fun finish(titles: Map<String, String> = emptyMap()): PdfFinished = withContext(Dispatchers.IO) {
        if (encrypted || (fonts == null && titles.isEmpty())) return@withContext PdfFinished(bytes, 0, encrypted)
        val pdf = try {
            Loader.loadPDF(bytes)
        } catch (_: InvalidPasswordException) {
            return@withContext PdfFinished(bytes, 0, true)
        }
        pdf.use {
            if (pdf.isEncrypted) return@withContext PdfFinished(bytes, 0, true)
            val changedFonts = fonts?.let { subsetFonts(pdf, it) } ?: false
            val translated = replaceTitles(pdf, titles)
            if (!changedFonts && translated == 0) return@withContext PdfFinished(bytes, 0, false)
            val output = ByteArrayOutputStream()
            pdf.save(output)
            PdfFinished(output.toByteArray(), translated, false)
        }
    }

    private fun subsetFonts(document: PDDocument, fonts: PdfFonts): Boolean {
        val subset = mutableMapOf<Int, ByteArray>()
        val visited = Collections.newSetFromMap(IdentityHashMap<COSStream, Boolean>())
        for (entry in document.document.getObjectsByType(COSName.FONT_DESC)) {
            val descriptor = entry.`object` as? COSDictionary ?: continue
            val stream = descriptor.getDictionaryObject(COSName.FONT_FILE2) as? COSStream ?: continue
            if (!visited.add(stream)) continue
            val length = stream.getInt(COSName.LENGTH1)
            val bytes = when (length) {
                fonts.regular.size -> fonts.regular
                fonts.bold.size -> fonts.bold
                else -> continue
            }
            val subsetBytes = subset.getOrPut(length) {
                val codePoints = if (bytes === fonts.bold) boldCodePoints else regularCodePoints
                val used = if (codePoints.isEmpty()) intArrayOf(32) else codePoints
                checkNotNull(PdfEditingNative.subsetFont(bytes, used)) {
                    "PDF の埋め込みフォントをサブセットにできません"
                }
            }
            stream.removeItem(COSName.DECODE_PARMS)
            stream.createOutputStream(COSName.FLATE_DECODE).use { it.write(subsetBytes) }
            stream.setInt(COSName.LENGTH1, subsetBytes.size)
        }
        return subset.isNotEmpty()
    }
}

private fun replaceTitles(document: PDDocument, titles: Map<String, String>): Int {
    if (titles.isEmpty()) return 0
    val root = document.documentCatalog.documentOutline?.firstChild ?: return 0
    val byKey = titles.mapKeys { PdfTitles.titleKey(it.key) }
    val pending = ArrayDeque<PDOutlineItem>().apply { add(root) }
    val visited = Collections.newSetFromMap(IdentityHashMap<COSDictionary, Boolean>())
    var replaced = 0
    while (pending.isNotEmpty()) {
        val item = pending.removeLast()
        if (!visited.add(item.cosObject)) continue
        item.nextSibling?.let(pending::addLast)
        item.firstChild?.let(pending::addLast)
        val translation = item.title?.let { byKey[PdfTitles.titleKey(it)] }
        if (!translation.isNullOrBlank() && translation != item.title) {
            item.title = translation
            replaced++
        }
    }
    return replaced
}
