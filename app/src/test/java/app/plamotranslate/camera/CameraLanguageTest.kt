package app.plamotranslate.camera

import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraLanguageTest {
    @Test
    fun 読める認識器がある言語だけを選べる() {
        assertEquals(OcrLanguage.KOREAN, Language.KO.ocrLanguage)
        assertEquals(OcrLanguage.CHINESE, Language.ZH_HANT.ocrLanguage)
        assertEquals(OcrLanguage.LATIN, Language.VI.ocrLanguage)
        assertEquals(OcrLanguage.JAPANESE, Language.JA.ocrLanguage)
        // キリル文字・タイ文字は読める認識器が無い
        assertFalse(Language.RU in CAMERA_SOURCES)
        assertFalse(Language.TH in CAMERA_SOURCES)
        assertEquals(29, CAMERA_SOURCES.size)
    }

    @Test
    fun 訳す先と同じ言語は原文に選ばない() {
        // 日本語へ訳すときは日本語を出さず、英語へ訳すときは日本語を選べる
        assertFalse(Language.JA in cameraSources(Language.JA))
        assertTrue(Language.JA in cameraSources(Language.EN))
        assertFalse(Language.EN in cameraSources(Language.EN))
        assertEquals(listOf(Language.KO, Language.EN, Language.ZH), cameraQuickSources(Language.JA))
        assertEquals(listOf(Language.JA, Language.KO, Language.ZH), cameraQuickSources(Language.EN))
        // 繁体字へ訳すときは中国語（簡体字）も出さない
        assertFalse(Language.ZH in cameraQuickSources(Language.ZH_HANT))
    }

    @Test
    fun チップの言語は選べる言語に含まれる() {
        Language.entries.forEach { target -> assertTrue(cameraSources(target).containsAll(cameraQuickSources(target))) }
    }
}
