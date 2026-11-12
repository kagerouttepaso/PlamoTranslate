package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TranslationChecksTest {
    @Test
    fun 原文のまま写した行は訳されていない() {
        assertTrue(TranslationChecks.untranslated("오늘 날씨가 좋네요", "오늘 날씨가 좋네요", Language.JA))
    }

    @Test
    fun 日本語の字が無い訳は訳されていない() {
        assertTrue(TranslationChecks.untranslated("Bonjour tout le monde, comment allez-vous", "Hello everyone, how are you", Language.JA))
        assertFalse(TranslationChecks.untranslated("오늘 날씨가 좋네요", "今日は天気がいいですね", Language.JA))
        // 漢字だけの訳も日本語として認める
        assertFalse(TranslationChecks.untranslated("注意事项", "注意事項", Language.JA))
    }

    @Test
    fun 短い英字は原文のままでよい() {
        assertFalse(TranslationChecks.untranslated("GitHub Copilot", "GitHub Copilot", Language.JA))
    }

    @Test
    fun 訳す先が日本語以外なら日本語の字は見ない() {
        assertFalse(TranslationChecks.untranslated("今日は天気がいいですね", "The weather is nice today", Language.EN))
    }

    @Test
    fun 繰り返しは原文より長くなってから打ち切る() {
        val loop = "これはテストです。".repeat(10)
        val cut = TranslationChecks.runaway(loop, 1, sourceChars = 10)
        assertTrue(cut in 1 until loop.length)
        // 原文にもともと繰り返しがある（表の単位）なら、原文の長さまでは止めない
        assertEquals(0, TranslationChecks.runaway("分 分 分 分 分 分 分 分 分 分", 1, sourceChars = 40))
    }

    @Test
    fun 送った行数より多い行は打ち切る() {
        assertEquals("a\nb".length, TranslationChecks.runaway("a\nb\nc\nd", 2, sourceChars = 100))
    }

    @Test
    fun 長い段落は文の切れ目で分ける() {
        val text = "これは文です。".repeat(400)
        val parts = TranslationChecks.splitLong(text, 1200)
        assertTrue(parts.size > 1)
        assertTrue(parts.all { it.length <= 1200 })
        assertEquals(text, parts.joinToString(""))
    }

    @Test
    fun 同時に訳すときは長文を文ごとに細かく分ける() {
        // Discordのボットの長文くらい（約430字）
        val text = "Thanks, the log shows why. Auto wanted the Xclipse driver, but it isn't installed on your phone, " +
            "so Steam ran on the phone's own graphics driver and drew about one frame every two seconds. " +
            "That is a bug in how Auto falls back, not a problem with your phone, and I've queued it with your log. " +
            "I'll post here when there is a test build."
        val parts = TranslationChecks.splitLong(text, TranslationChecks.PARALLEL_SPLIT)
        // 4文が3片になる。2文目は150字を超えるが文の途中では分けず、3・4文目は合わせて150字以内なので1片にまとめる
        assertEquals(3, parts.size)
        assertTrue(parts[1].startsWith("Auto wanted") && parts[1].length > TranslationChecks.PARALLEL_SPLIT)
        assertEquals(text, parts.joinToString(""))
        // 短い投稿は分けない
        assertEquals(listOf("Awesome, thanks a ton!"), TranslationChecks.splitLong("Awesome, thanks a ton!", TranslationChecks.PARALLEL_SPLIT))
    }

    @Test
    fun 片の訳をつなぐ() {
        assertEquals("今日は晴れ。明日は雨。", TranslationChecks.joinPieces(listOf("今日は晴れ。", "明日は雨。")))
        assertEquals("It is sunny. It rains.", TranslationChecks.joinPieces(listOf("It is sunny.", "It rains.")))
    }
}
