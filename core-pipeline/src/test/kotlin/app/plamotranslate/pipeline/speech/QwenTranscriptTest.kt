package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Test

class QwenTranscriptTest {
    @Test fun 言語ラベルと本文と出どころを分ける() {
        assertEquals(Recognition("안녕하세요", "ko"), QwenTranscript.parse("language Korean<asr_text>안녕하세요<|im_end|>続き", null))
        assertEquals(Recognition("text", "en", source = LanguageSource.SPECIFIED), QwenTranscript.parse("language Japanese<asr_text>text", "en"))
        for (raw in listOf("本文", "language Unknown<asr_text>本文", "<asr_text>本文")) {
            assertEquals(Recognition("本文", null, source = LanguageSource.GUESSED), QwenTranscript.parse(raw, null))
        }
    }

    @Test fun 役だけなら空にし文の中の語は残す() {
        listOf("system", "System.", "システム。", "시스템", "The system.", "language English<asr_text>User.", "assistant<|im_end|>", "<|im_end|>").forEach {
            assertEquals("", QwenTranscript.parse(it, null).text)
        }
        listOf("This system is not well.", "Ask the assistant.", "A user can speak.", "全体のシステムの協調性", "", "the").forEach {
            assertEquals(it, QwenTranscript.parse(it, null).text)
        }
    }
}
