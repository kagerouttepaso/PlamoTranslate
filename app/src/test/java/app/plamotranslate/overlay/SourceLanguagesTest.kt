package app.plamotranslate.overlay

import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class SourceLanguagesTest {
    @Test
    fun 読める認識器がある言語だけを選べる() {
        assertEquals(OcrLanguage.KOREAN, Language.KO.ocrLanguage)
        assertEquals(OcrLanguage.CHINESE, Language.ZH_HANT.ocrLanguage)
        assertEquals(OcrLanguage.LATIN, Language.VI.ocrLanguage)
        assertEquals(OcrLanguage.JAPANESE, Language.JA.ocrLanguage)
        assertEquals(OcrLanguage.AUTO, (null as Language?).recognizers)
        // キリル文字・タイ文字は読める認識器が無い
        assertFalse(Language.RU in READABLE_SOURCES)
        assertFalse(Language.TH in READABLE_SOURCES)
        assertEquals(29, READABLE_SOURCES.size)
    }

    @Test
    fun 保存値は言語コードで戻し自動と読めない値と選べない言語は自動にする() {
        val allowed = READABLE_SOURCES - Language.JA
        assertEquals(Language.FR, storedSource(Language.FR.storedCode, null, allowed))
        assertNull(storedSource((null as Language?).storedCode, "KOREAN", allowed))
        assertNull(storedSource("xx", null, allowed))
        assertNull(storedSource(Language.JA.storedCode, null, allowed))
    }

    @Test
    fun 以前の版の保存値は同じ認識器の組で読む言語へ読み替える() {
        val allowed = READABLE_SOURCES - Language.JA
        assertEquals(Language.EN, storedSource(null, "LATIN", allowed))
        assertEquals(Language.EN, storedSource(null, "ENGLISH", allowed))
        assertEquals(Language.ZH, storedSource(null, "CHINESE", allowed))
        assertNull(storedSource(null, "JAPANESE", allowed))
        assertNull(storedSource(null, null, allowed))
    }

    @Test
    fun ラテン文字で書く言語は中核の文字の種類から決まりセルビア語は入れない() {
        val latin = Language.entries.filter { it.ocrLanguage == OcrLanguage.LATIN }.toSet()
        assertEquals(25, latin.size)
        assertEquals(false, Language.SR in latin)
        assertEquals(null, Language.RU.ocrLanguage)
    }
}
