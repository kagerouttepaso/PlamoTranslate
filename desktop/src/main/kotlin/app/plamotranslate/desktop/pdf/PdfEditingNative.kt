package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfColor
import app.plamotranslate.pipeline.pdf.PdfGlyph
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.Charset

internal object PdfEditingNative {
    init {
        System.loadLibrary("mockingword-pdf")
    }

    external fun openBytes(bytes: ByteArray): Long
    external fun isEncrypted(handle: Long): Boolean
    external fun characters(handle: Long, index: Int): ByteArray?
    external fun links(handle: Long, index: Int): ByteArray?
    external fun outline(handle: Long): ByteArray?
    external fun search(handle: Long, page: Int, query: String, flags: Int): ByteArray?
    external fun characterAt(handle: Long, page: Int, x: Double, y: Double, tolerance: Double): Int
    external fun selection(handle: Long, page: Int, start: Int, length: Int): ByteArray?
    external fun viewport(handle: Long, index: Int): DoubleArray?
    external fun embedFonts(handle: Long, regular: ByteArray, bold: ByteArray): Boolean
    external fun writePage(
        handle: Long, page: Int, removeObjects: IntArray, retainChars: IntArray,
        contents: Array<String?>, sourceChars: IntArray, coordinates: DoubleArray, moveIndices: IntArray,
        moveBoxes: DoubleArray, removeLinks: IntArray,
    ): Int
    external fun save(handle: Long): ByteArray?
    external fun subsetFont(bytes: ByteArray, codePoints: IntArray): ByteArray?
}

internal class PdfPacket(bytes: ByteArray) {
    private val data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)

    fun int(): Int = data.int
    fun number(): Double = data.double
    fun string(charset: Charset = Charsets.UTF_8): String = String(ByteArray(int()).also(data::get), charset)
    fun box() = PdfBounds(number(), number(), number(), number())
    fun boxes() = List(int()) { box() }

    private data class Style(val path: List<Int>?, val name: String, val weight: Int, val color: PdfColor)

    fun glyphs(): List<PdfGlyph> {
        val styles = List(int()) {
            val path = List(int()) { int() }.ifEmpty { null }
            val name = string()
            val weight = int()
            val argb = int()
            Style(path, name, weight, PdfColor(argb ushr 16 and 255, argb ushr 8 and 255, argb and 255))
        }
        return List(int()) { index ->
            val code = int()
            val style = styles.getOrNull(int())
            val flags = int()
            val link = int().takeIf { it >= 0 }
            val x = number()
            val y = number()
            val bounds = box()
            val size = number()
            val unmappedAdvance = number().takeIf { it >= 0 }
            val generated = flags and 1 != 0
            val text = if (Character.isValidCodePoint(code) && code !in 0xD800..0xDFFF) {
                String(Character.toChars(code))
            } else "\ufffd"
            PdfGlyph(
                index, text, x, y, bounds, if (generated) "" else style?.name.orEmpty(),
                if (generated) 0 else style?.weight ?: 0, style?.color ?: PdfColor.Black,
                if (generated) null else style?.path, size, generated, flags and 2 != 0, link, unmappedAdvance,
            )
        }
    }

    private fun destination(): PdfDestination? = when (int()) {
        0 -> null
        1 -> PdfDestination.Page(int(), number().takeIf(Double::isFinite),
            number().takeIf(Double::isFinite), number().takeIf(Double::isFinite))
        2 -> PdfDestination.Uri(string())
        else -> error("PDF の飛び先を読み取れません")
    }

    fun links() = List(int()) {
        PdfLink(int(), box(), destination(), boxes().distinct())
    }

    fun outline(): List<PdfBookmark> {
        data class Entry(val parent: Int, val title: String, val destination: PdfDestination?) {
            val children = mutableListOf<PdfBookmark>()
        }
        val entries = List(int()) { Entry(int(), string(Charsets.UTF_16LE), destination()) }
        val root = mutableListOf<PdfBookmark>()
        for (entry in entries.asReversed()) {
            val bookmark = PdfBookmark(entry.title, entry.destination, entry.children.asReversed())
            (entries.getOrNull(entry.parent)?.children ?: root).add(bookmark)
        }
        return root.asReversed()
    }
}
