package app.plamotranslate.desktop

internal object PdfNative {
    init {
        System.loadLibrary("mockingword-pdf")
    }

    external fun open(path: String): Long
    external fun close(handle: Long)
    external fun pageCount(handle: Long): Int
    external fun pageSize(handle: Long, index: Int): FloatArray?
    external fun render(handle: Long, index: Int, width: Int, height: Int): IntArray?
    external fun glyphs(handle: Long, index: Int): FloatArray?
}
