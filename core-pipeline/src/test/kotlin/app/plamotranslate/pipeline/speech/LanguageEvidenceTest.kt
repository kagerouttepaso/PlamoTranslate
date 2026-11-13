package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 話し手の交代の根拠になる言語か（[LanguageEvidence]）。例は作者の会話の録音で、誤って区切った判定。 */
class LanguageEvidenceTest {
    private val jaKo = SpeechLanguages(setOf("ja", "ko", "en"))

    private fun of(text: String, lang: String?, languages: SpeechLanguages = jaKo) = LanguageEvidence.of(Recognition(text, lang), languages)

    @Test
    fun 中国語の相手でなければ中国語の判定は日本語と同じ話し手とみなす() {
        assertEquals("ja", LanguageEvidence.group("zh", separateChinese = false))
        assertEquals("ja", LanguageEvidence.group("yue", separateChinese = false))
        assertEquals("zh", LanguageEvidence.group("zh", separateChinese = true))
        assertEquals("zh", LanguageEvidence.group("yue", separateChinese = true))
        assertEquals("ko", LanguageEvidence.group("ko", separateChinese = true))
    }

    @Test
    fun 確かな判定は根拠にする() {
        assertEquals("ko", of("안녕하세요", "ko"))
        assertEquals("en", of("Nice to meet you.", "en"))
        assertEquals("ja", of("この3段階の", "ja"))
        // 漢字の多い日本語を中国語と判定しても、日本語の組の根拠になる
        assertEquals("ja", of("嗯手哒呢", "zh"))
    }

    @Test
    fun 判定した言語と文字の種類が合わなければ根拠にしない() {
        assertNull(of("ま。", "ko"))
        assertNull(of("これ?", "ko"))
        assertNull(of("やぱ.", "en"))
        assertNull(of("我。", "en"))
        assertNull(of("てます。", "zh", SpeechLanguages(setOf("ja", "zh"), separateChinese = true)))
    }

    @Test
    fun 中身が1語だけなら根拠にしない() {
        assertNull(of("Now.", "en"))
        assertNull(of("Okay.", "en"))
        assertNull(of("응.", "ko"))
        assertNull(of("はい", "ja"))
    }

    @Test
    fun 会話の言語でなければ根拠にしない() {
        assertNull(of("Nice to meet you.", "en", SpeechLanguages(setOf("ja", "ko"))))
        assertEquals("en", of("Nice to meet you.", "en", SpeechLanguages.ANY))
    }

    @Test
    fun 雑音は根拠にならない() {
        assertNull(of("", null))
    }
}
