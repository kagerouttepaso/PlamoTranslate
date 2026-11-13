package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 話し手の交代の根拠になる言語か（[LanguageEvidence]）。 */
class LanguageEvidenceTest {
    @Test
    fun 中国語の相手でなければ中国語の判定は日本語と同じ話し手とみなす() {
        assertEquals("ja", LanguageEvidence.group("zh", separateChinese = false))
        assertEquals("ja", LanguageEvidence.group("yue", separateChinese = false))
        assertEquals("zh", LanguageEvidence.group("zh", separateChinese = true))
        assertEquals("zh", LanguageEvidence.group("yue", separateChinese = true))
        assertEquals("ko", LanguageEvidence.group("ko", separateChinese = true))
    }

    @Test
    fun 雑音は根拠にならない() {
        assertNull(LanguageEvidence.of(Recognition("", null), separateChinese = false))
        assertEquals("ko", LanguageEvidence.of(Recognition("안녕하세요", "ko"), separateChinese = false))
    }
}
