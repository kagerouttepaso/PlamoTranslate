package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    /** [lang] を一番（0.8）、ほかの候補を残りで分ける識別。 */
    private class FakeIdentifier(private val lang: String?) : LanguageIdentifier {
        override fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>? {
            val all = candidates ?: setOfNotNull(lang)
            if (lang == null || all.isEmpty()) return null
            val others = all - lang
            return if (lang in all) others.associateWith { 0.2f / others.size } + (lang to 0.8f) else all.associateWith { 1f / all.size }
        }
        override fun close() = Unit
    }

    private val audio = FloatArray(16_000)

    @Test
    fun 識別した言語が会話の言語なら指定して書き起こす() {
        val asr = FakeRecognizer("Alice et moi sommes allés à Paris")
        val r = kotlinx.coroutines.runBlocking { IdentifiedRecognizer(FakeIdentifier("fr"), asr).identifyAndRecognize(audio, SpeechLanguages(setOf("ja", "fr")), priority = RecognitionPriority.FINAL) }
        assertEquals("fr", asr.requested)
        assertEquals("fr", r.lang)
    }

    @Test
    fun 会話の言語の外の言語は指定しない() {
        // 端末で、アラビア語を Whisper tiny がトルコ語と誤った。トルコ語を指定すると書き起こしが崩れるので、会話の言語の中で比べる
        val asr = FakeRecognizer("إطلالات مكياج عيون ذهبية")
        kotlinx.coroutines.runBlocking { IdentifiedRecognizer(FakeIdentifier("tr"), asr).identifyAndRecognize(audio, SpeechLanguages(setOf("ja", "ar")), priority = RecognitionPriority.FINAL) }
        assertTrue(asr.requested in setOf("ja", "ar"))
    }

    @Test
    fun 文字の種類が同じ言語どうしは決めない() {
        val asr = FakeRecognizer("Guten Tag")
        val r = kotlinx.coroutines.runBlocking { IdentifiedRecognizer(FakeIdentifier(null), asr).identifyAndRecognize(audio, SpeechLanguages(setOf("en", "de")), priority = RecognitionPriority.FINAL) }
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

    @Test
    fun 識別できなければカードの言語として書き起こす() {
        // 言語を指定しないと Qwen3-ASR が中国語で書くことがあるので、カードの言語を渡す
        val asr = FakeRecognizer("ですね")
        val r = kotlinx.coroutines.runBlocking { IdentifiedRecognizer(FakeIdentifier(null), asr).identifyAndRecognize(audio, SpeechLanguages(setOf("ja", "ru")), fallback = "ja", priority = RecognitionPriority.FINAL) }
        assertEquals("ja", asr.requested)
        assertEquals("ja", r.lang)
    }

    @Test
    fun 日本語では使わない漢字があれば日本語とみなさない() {
        assertEquals(1, Script.japaneseHan("我们说话"))
        assertEquals(4, Script.japaneseHan("東京駅で会う"))
        assertNull(ScriptLanguage.guess("我们说话", setOf("ja", "ru")))
        // 漢字だけの日本語は、日本語と中国語のどちらにも同じだけ合うので決めない
        assertNull(ScriptLanguage.guess("東京大学", setOf("ja", "zh")))
        assertEquals("ja", ScriptLanguage.guess("東京大学", setOf("ja", "ko")))
        assertEquals("zh", ScriptLanguage.guess("我们说话", setOf("ja", "zh")))
    }
}
