package app.plamotranslate.screen

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.sameAs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLanguageTest {
    @Test
    fun 選べる言語は行かページのどちらか一方に出る() {
        val shown = SCREEN_QUICK_SOURCES + SCREEN_OTHER_SOURCES
        assertEquals(SCREEN_SOURCES.sorted(), shown.sorted())
        assertEquals(listOf(Language.ZH, Language.KO, Language.EN), SCREEN_QUICK_SOURCES)
        // 訳す先（日本語）は原文に出さない
        assertTrue(SCREEN_SOURCES.none { it.sameAs(SCREEN_TARGET) })
    }
}
