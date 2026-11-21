package app.plamotranslate.pipeline.pdf

object PdfTranslationPlan {
    fun pages(current: Int, count: Int, ahead: Int): List<Int> =
        if (current !in 0 until count) emptyList() else (current..minOf(count - 1, current + ahead)).toList()

    fun next(current: Int, count: Int, pending: Map<Int, List<Int>>): Int? {
        val pages = pages(current, count, ahead = 1)
        if (pending[pages.firstOrNull()] == null) return null
        return pages.firstOrNull { !pending[it].isNullOrEmpty() }
    }
}
