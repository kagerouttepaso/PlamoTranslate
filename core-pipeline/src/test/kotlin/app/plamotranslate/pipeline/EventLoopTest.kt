package app.plamotranslate.pipeline

import org.junit.Assert.assertEquals
import org.junit.Test

/** 出来事を順に渡す入口（[EventLoop]）。 */
class EventLoopTest {
    @Test
    fun 作業の中で出た出来事は今の作業をすべて行ってから届いた順に渡す() {
        val log = mutableListOf<String>()
        lateinit var loop: EventLoop<String, String>
        loop = EventLoop(
            handle = { event -> log += "handle $event"; if (event == "A") listOf("a1", "a2") else listOf("${event.lowercase()}1") },
            perform = { effect ->
                log += "perform $effect"
                // 1つ目の作業の中で2つの出来事が届く
                if (effect == "a1") { loop.dispatch("B"); loop.dispatch("C") }
            },
        )
        loop.dispatch("A")
        assertEquals(
            listOf("handle A", "perform a1", "perform a2", "handle B", "perform b1", "handle C", "perform c1"),
            log,
        )
    }

    @Test
    fun 始めるときの作業の中で出た出来事はその後で渡す() {
        val log = mutableListOf<String>()
        lateinit var loop: EventLoop<String, String>
        loop = EventLoop(handle = { listOf("x") }, perform = { log += it; if (it == "s") loop.dispatch("E") })
        loop.run(listOf("s", "t"))
        assertEquals(listOf("s", "t", "x"), log)
    }
}
