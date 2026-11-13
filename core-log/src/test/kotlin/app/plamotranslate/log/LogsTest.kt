package app.plamotranslate.log

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsTest {
    private val lines = mutableListOf<String>()
    private val sink = LogSink { level, _, message, error -> lines += "$level $message${error?.let { " ${it.message}" } ?: ""}" }

    @After
    fun reset() {
        Logs.install(emptyList(), userText = false)
    }

    @Test
    fun 差した書き先へ書く() {
        Logs.install(listOf(sink), userText = false)
        Logs.i("始めた")
        Logs.w("失敗", IllegalStateException("理由"))
        assertEquals(listOf("INFO 始めた", "WARN 失敗 理由"), lines)
    }

    @Test
    fun 正式版では利用者の文を書かず文も作らない() {
        Logs.install(listOf(sink), userText = false)
        var made = false
        Logs.text { made = true; "こんにちは → Hello" }
        assertEquals(emptyList<String>(), lines)
        assertEquals(false, made)
        Logs.install(listOf(sink), userText = true)
        Logs.text { "こんにちは → Hello" }
        assertEquals(listOf("DEBUG こんにちは → Hello"), lines)
    }

    @Test
    fun 書き先の失敗でほかの書き先と呼んだ側を止めない() {
        Logs.install(listOf(LogSink { _, _, _, _ -> error("壊れた") }, sink), userText = false)
        Logs.e("落ちた")
        assertEquals(listOf("ERROR 落ちた"), lines)
    }
}
