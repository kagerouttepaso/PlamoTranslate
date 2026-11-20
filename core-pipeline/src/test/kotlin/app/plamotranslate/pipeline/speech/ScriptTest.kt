package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 言語と文字の種類（[Script]）。どの言語がどの文字で書かれるかの正本で、画面翻訳・カメラ翻訳の認識器の組もここから決まる。 */
class ScriptTest {
    @Test
    fun アプリの言語はすべて文字の種類が決まり挙げた言語だけがその他になる() {
        // 今の認識器で読めない文字の言語（アルメニア文字・グルジア文字・クメール文字・インド系の文字・ビルマ文字・シンハラ文字）
        val other = setOf(
            Language.HY, Language.KA, Language.KM, Language.GU, Language.TA, Language.TE, Language.BN, Language.PA,
            Language.ML, Language.MY, Language.SI,
        )
        // 言語を足したら、ここで文字の種類を決め忘れていないかが分かる（挙げていない言語はその他になるため）
        assertEquals(other, Language.entries.filter { Script.of(it) == Script.OTHER }.toSet())
        assertEquals(25, Language.entries.count { Script.of(it) == Script.LATIN })
    }

    @Test
    fun 合い方はその言語の文字が占める割合で同じ文字の言語は同じ値になる() {
        val counts = ScriptCounts.of("Hello 안녕하세요")
        assertEquals(5f / 10, counts.fit("en"), 0.001f)
        assertEquals(counts.fit("en"), counts.fit("fr"), 0f)
        assertTrue(counts.fits("ko"))
        // 記号・数字・空白は数えない
        assertEquals(0, ScriptCounts.of("123 !?").letters)
        assertFalse(ScriptCounts.of("123 !?").fits("ja"))
    }

    @Test
    fun 日本語は日本語でも使う漢字だけを数え仮名があれば中国語に合わない() {
        // 「们」「说」「话」は日本語の文字コードに無い（簡体字）
        val chinese = ScriptCounts.of("我们说话很好")
        assertEquals(0.5f, chinese.fit("ja"), 0.001f)
        assertEquals(1f, chinese.fit("zh"), 0.001f)
        assertEquals(0f, ScriptCounts.of("今日は晴れ").fit("zh"), 0f)
    }

    @Test
    fun 当てるのは合う候補のうち合い方の一番高いもので同じ高さなら決めない() {
        val pick = { text: String, candidates: List<String> -> ScriptLanguage.pick(ScriptCounts.of(text), candidates) { it } }
        assertEquals("zh", pick("我们说话很好", listOf("ja", "zh")))
        assertEquals("ja", pick("我们说话很好", listOf("ja", "ko")))
        assertNull(pick("東京大学", listOf("ja", "zh")))
        assertNull(pick("Merci beaucoup", listOf("en", "fr")))
        assertNull(pick("こんにちは 안녕 hello", listOf("ja", "ko", "en")))
    }

    @Test
    fun 絵文字は数えず中黒は仮名に数えない() {
        assertEquals(2, ScriptCounts.of("😀😀안녕").letters)
        assertTrue(ScriptCounts.of("中华・人民").fits("zh"))
    }
}
