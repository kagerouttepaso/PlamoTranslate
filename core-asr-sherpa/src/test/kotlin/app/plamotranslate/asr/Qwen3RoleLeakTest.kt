package app.plamotranslate.asr

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Qwen3-ASR が話していない音声に返す、会話の型の役の名前だけの結果（[Qwen3Recognizer.isRoleLeak]）。 */
class Qwen3RoleLeakTest {
    @Test
    fun 役の名前だけの結果は話していないものとみなし文の中の語は残す() {
        listOf("system", "System.", "システム。", "시스템", "The system.").forEach { assertTrue(it, Qwen3Recognizer.isRoleLeak(it)) }
        listOf("This system is not well.", "全体のシステムの協調性", "", "the").forEach { assertFalse(it, Qwen3Recognizer.isRoleLeak(it)) }
    }
}
