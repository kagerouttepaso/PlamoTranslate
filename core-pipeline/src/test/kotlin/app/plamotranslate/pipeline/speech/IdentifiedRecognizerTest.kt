package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifiedRecognizerTest {
    /** 指定された言語を覚え、決まった文を返す認識器。 */
    private class FakeRecognizer(private val text: String) : SpeechRecognizer {
        var requested: String? = "未指定"
        override fun recognize(samples: FloatArray) = Recognition(text, null)
        override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
            requested = language
            return Recognition(text, null)
        }
        override fun close() = Unit
    }

    private class FakeIdentifier(private val lang: String?) : LanguageIdentifier {
        override fun identify(samples: FloatArray) = lang
        override fun close() = Unit
    }

    private val audio = FloatArray(16_000)

    @Test
    fun 識別した言語が会話の言語なら指定して書き起こす() {
        val asr = FakeRecognizer("Alice et moi sommes allés à Paris")
        val r = IdentifiedRecognizer(FakeIdentifier("fr"), asr).recognize(audio, SpeechLanguages(setOf("ja", "fr")))
        assertEquals("fr", asr.requested)
        assertEquals("fr", r.lang)
    }

    @Test
    fun 会話の言語の外なら指定せず文字の種類で決める() {
        // 端末で、アラビア語を Whisper tiny がトルコ語と誤った。トルコ語を指定すると書き起こしが崩れる
        val asr = FakeRecognizer("إطلالات مكياج عيون ذهبية")
        val r = IdentifiedRecognizer(FakeIdentifier("tr"), asr).recognize(audio, SpeechLanguages(setOf("ja", "ar")))
        assertNull(asr.requested)
        assertEquals("ar", r.lang)
    }

    @Test
    fun 文字の種類が同じ言語どうしは決めない() {
        val asr = FakeRecognizer("Guten Tag")
        val r = IdentifiedRecognizer(FakeIdentifier(null), asr).recognize(audio, SpeechLanguages(setOf("en", "de")))
        assertNull(r.lang)
    }

    @Test
    fun 漢字だけの文は中国語が会話に無ければ日本語() {
        assertEquals("ja", ScriptLanguage.guess("東京駅", setOf("ja", "ru")))
        assertNull(ScriptLanguage.guess("東京駅", setOf("ja", "zh")))
        assertEquals("ru", ScriptLanguage.guess("Барсук, живущий", setOf("ja", "ru")))
        assertEquals("hi", ScriptLanguage.guess("नमस्ते दोस्त", setOf("ja", "hi")))
    }

    @Test
    fun 日韓英中の外の言語も話し手の交代の根拠になる() {
        val languages = SpeechLanguages(setOf("ja", "ru"))
        assertEquals("ru", LanguageEvidence.of(Recognition("Барсук живущий в зоопарке", "ru"), languages))
        // 文字が合わないものと短すぎるものは根拠にしない
        assertNull(LanguageEvidence.of(Recognition("こんにちは", "ru"), languages))
        assertNull(LanguageEvidence.of(Recognition("Да", "ru"), languages))
    }
}
