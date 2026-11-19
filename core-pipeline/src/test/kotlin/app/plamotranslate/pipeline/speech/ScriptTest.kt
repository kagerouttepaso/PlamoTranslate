package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language
import org.junit.Assert.assertEquals
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
}
