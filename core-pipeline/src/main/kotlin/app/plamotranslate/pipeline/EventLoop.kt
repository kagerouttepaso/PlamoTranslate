package app.plamotranslate.pipeline

/**
 * 出来事を順に制御（[handle]）へ渡し、返った作業を [perform] で行う。作業を行っている間に届いた出来事（作業の中から同期で
 * 出たものを含む）は、今の出来事の作業をすべて行ってから、届いた順に渡す。作業の途中で制御へ入り直すと、外側の残りの作業が
 * 内側の出来事の後の状態で走る（取り消したものの作業が後から走る）ため。画面翻訳とカメラ翻訳の制御の入口が使う。
 * 同じスレッド（メインスレッド）だけで使う。
 */
class EventLoop<E, F>(private val handle: (E) -> List<F>, private val perform: (F) -> Unit) {
    private val pending = ArrayDeque<E>()
    private var running = false

    fun dispatch(event: E) {
        pending.addLast(event)
        drain()
    }

    /** 制御を通さずに決まった作業（始めるときの作業）を行う。行っている間に届いた出来事は、その後で順に渡す。 */
    fun run(effects: List<F>) {
        check(!running) { "作業の途中で始め直さない" }
        running = true
        try {
            effects.forEach(perform)
        } finally {
            running = false
        }
        drain()
    }

    private fun drain() {
        if (running) return
        running = true
        try {
            while (pending.isNotEmpty()) handle(pending.removeFirst()).forEach(perform)
        } finally {
            running = false
        }
    }
}
