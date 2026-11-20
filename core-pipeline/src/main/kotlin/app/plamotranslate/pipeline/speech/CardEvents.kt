package app.plamotranslate.pipeline.speech

/**
 * カードの確定（[SpeechEvent.CardFinished]）を、カードを開いた順に出す（docs/設計/会話翻訳.md「カードの区切り方」）。
 * 確定に要る書き起こしは前後のカードで届く順が入れ替わりうるので、先頭のカードが確定するまで、後ろのカードの確定は持っておく。
 * すべてのカードの後に出す出来事（止め終えた [SpeechEvent.Flushed]）は、最後のカードが確定した後に出す。
 * 開く・閉じる・途中の文はその場で出すので、ここを通さない（[SpeechSegmenter]）。
 * 状態を持つので、[SpeechSegmenter] と同じ1本のスレッドから呼ぶ。
 */
internal class CardEvents(private val emit: (SpeechEvent) -> Unit) {
    private class Entry(val id: Long) {
        /** 前のカードが確定するまで待たせている確定。 */
        var held: SpeechEvent? = null

        /** このカードが確定した後に出す出来事。 */
        val after = mutableListOf<SpeechEvent>()
        var done = false
    }

    /** まだ確定していないカード（開いた順）。先頭のカードの確定だけをその場で出す。 */
    private val cards = ArrayDeque<Entry>()

    /** 確定していないカードが無いか。 */
    val idle: Boolean get() = cards.isEmpty()

    /** カード [id] を開いた。確定はこの順に出す。 */
    fun opened(id: Long) {
        cards.addLast(Entry(id))
    }

    /** カード [id] が確定したか（まだ開いていれば false、確定して外れていれば true）。 */
    fun finished(id: Long): Boolean = cards.firstOrNull { it.id == id }?.done ?: cards.none { it.id == id }

    /** カード [id] を [event] で確定する。前のカードが確定していなければ、確定するまで持っておく。 */
    fun finish(id: Long, event: SpeechEvent) {
        val entry = cards.first { it.id == id }
        if (cards.first() === entry) emit(event) else entry.held = event
        entry.done = true
        pump()
    }

    /** 今あるカードがすべて確定した後に [event] を出す。カードが無ければすぐ出す。 */
    fun afterAll(event: SpeechEvent) {
        val last = cards.lastOrNull()
        if (last == null) emit(event) else last.after += event
    }

    /** 持っている出来事ごとカードを忘れる（聞き直し）。 */
    fun clear() = cards.clear()

    /** 確定したカードを先頭から外し、次のカードの持っていた確定を出す。 */
    private fun pump() {
        while (true) {
            val head = cards.firstOrNull() ?: return
            if (!head.done) return
            cards.removeFirst()
            head.after.forEach(emit)
            val next = cards.firstOrNull() ?: return
            next.held?.let(emit)
            next.held = null
        }
    }
}
