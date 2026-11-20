package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.CardTranscript.Phrase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 1枚のカードの書き起こしをつなぐ（[CardTranscript]）。 */
class CardTranscriptTest {
    private val text = CardTranscript(start = 0)

    private fun phrase(from: Long, end: Long, said: String? = null) =
        Phrase(from, end).also { p -> said?.let { p.recognition = Recognition(it, "ja") } }

    @Test
    fun `途中の文字は頭から続く届いた文だけをつなぎ、途中結果は最後の文の後ろのものだけを足す`() {
        val first = phrase(0, 100, "おはよう。")
        val second = phrase(100, 200)
        text.add(first)
        text.add(second)
        text.partial(200, Recognition("今日は", "ja"))
        // 2つ目の文が届いていないので、途中結果はつながない
        assertEquals("おはよう。", text.current()?.text)

        second.recognition = Recognition("いい天気。", "ja")
        assertEquals("おはよう。いい天気。今日は", text.current()?.text)

        // 文が増えたら、前の途中結果は古い
        text.add(phrase(200, 300, "晴れ。"))
        assertEquals("おはよう。いい天気。晴れ。", text.current()?.text)
    }

    @Test
    fun `同じ文字は続けて出さない`() {
        assertTrue(text.show("はい"))
        assertFalse(text.show("はい"))
        assertFalse(text.show(""))
        assertEquals("はい", text.shown)
    }

    @Test
    fun `確定は区切る位置までの文と残りがすべて届いてからつなぐ`() {
        text.add(phrase(0, 100, "はい。"))
        text.add(phrase(100, 300, "次の人の文"))
        assertNull(text.final())

        text.cut(200)
        val rest = phrase(100, 200)
        text.close(rest)
        assertNull(text.final())

        rest.recognition = Recognition("そうです。", "ja")
        assertEquals("はい。そうです。", text.final()?.text)
        assertEquals(100L, text.stitchedEnd)
    }
}
