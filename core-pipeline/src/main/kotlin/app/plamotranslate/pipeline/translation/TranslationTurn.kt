package app.plamotranslate.pipeline.translation

class TranslationTurn(slots: Int, private val hasWaiters: () -> Boolean) {
    private var openingSlots = slots
    var yielded = false
        private set

    fun <T : Any> next(take: () -> T?): T? {
        if (openingSlots == 0 && hasWaiters()) yielded = true
        if (yielded) return null
        return take().also { item ->
            openingSlots = if (item == null) 0 else (openingSlots - 1).coerceAtLeast(0)
        }
    }
}
