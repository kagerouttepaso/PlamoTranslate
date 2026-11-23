package app.plamotranslate.pipeline.translation

import org.junit.Assert.*
import org.junit.Test

class TranslationTurnTest {
    @Test fun `先頭の組が枠より少なくても後から足された文を先読みしない`() {
        val turn = TranslationTurn(4) { true }
        assertEquals("最初の文", turn.next { "最初の文" })
        assertNull(turn.next<String> { null })
        assertNull(turn.next<String> { error("待っている利用者の前で次の文を取った") })
        assertTrue(turn.yielded)
    }
}
