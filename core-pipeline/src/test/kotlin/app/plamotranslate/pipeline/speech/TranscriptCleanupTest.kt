package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** 聞き違いの判定（[TranscriptCleanup]）。 */
class TranscriptCleanupTest {
    @Test
    fun 中身のない聞き違いは空にする() {
        assertEquals("", TranscriptCleanup.dropFillers(".", "en"))
        assertEquals("", TranscriptCleanup.dropFillers("Yeah.", "en"))
        assertEquals("", TranscriptCleanup.dropFillers("I.", "en"))
        assertEquals("", TranscriptCleanup.dropFillers("。", "ja"))
        assertEquals("", TranscriptCleanup.dropFillers("嗯。", "zh"))
        assertEquals("", TranscriptCleanup.dropFillers("Yeah. Yeah.", "en"))
    }

    @Test
    fun 本当の返事と文は残す() {
        assertEquals("Yes.", TranscriptCleanup.dropFillers("Yes.", "en"))
        assertEquals("OK.", TranscriptCleanup.dropFillers("OK.", "en"))
        assertEquals("I see.", TranscriptCleanup.dropFillers("I see.", "en"))
        assertEquals("Yeah, I think so.", TranscriptCleanup.dropFillers("Yeah, I think so.", "en"))
        assertEquals("はい。", TranscriptCleanup.dropFillers("はい。", "ja"))
        assertEquals("Thank you", TranscriptCleanup.dropFillers("Thank you", "en"))
    }

    @Test
    fun 日本語と韓国語の頭と末尾の英語のあいづちを取る() {
        assertEquals("明日は雨らしいです。", TranscriptCleanup.dropFillers("明日は雨らしいです。Yeah.", "ja"))
        assertEquals("明日は雨らしいです。", TranscriptCleanup.dropFillers("I. 明日は雨らしいです。", "ja"))
        assertEquals("내일 비 온대요.", TranscriptCleanup.dropFillers("내일 비 온대요. Yeah.", "ko"))
        assertEquals("iPhoneを買った。", TranscriptCleanup.dropFillers("iPhoneを買った。", "ja"))
    }

    @Test
    fun 中身が残らなければ言語も無しにする() {
        assertEquals(Recognition("", null), TranscriptCleanup.clean(Recognition("Yeah.", "en")))
        assertEquals(Recognition("うちの中学", "ja"), TranscriptCleanup.clean(Recognition(" うちの中学 ", "ja")))
    }
}
