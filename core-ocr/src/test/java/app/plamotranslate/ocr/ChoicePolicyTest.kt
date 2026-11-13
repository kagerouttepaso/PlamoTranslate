package app.plamotranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 行を採る方針（[ChoicePolicy]）。読み手を足したら、ここの表の試験も足す。 */
class ChoicePolicyTest {
    @Test
    fun PP_OCR韓国語は確信度の下限未満の行を採らない() {
        assertTrue(ChoicePolicy.accepts(Source.PADDLE_KO, "업적", 0.99f))
        assertTrue(ChoicePolicy.accepts(Source.PADDLE_KO, "거두!", ChoicePolicy.PADDLE_KO_MIN_CONFIDENCE))
        // 画素画の飾りを読み違えた行（2026-11-13、確信度0.16）
        assertFalse(ChoicePolicy.accepts(Source.PADDLE_KO, "이표원", 0.16f))
    }

    @Test
    fun PP_OCR韓国語はハングルの無い行を採らない() {
        // 数字や記号の行は ML Kit 韓国語に任せる
        assertFalse(ChoicePolicy.accepts(Source.PADDLE_KO, "13/224+", 0.99f))
    }

    @Test
    fun PP_OCR漫画用は漢字か仮名のある行だけ採る() {
        assertTrue(ChoicePolicy.accepts(Source.PADDLE_ZH_JA, "你好", 0.99f))
        assertTrue(ChoicePolicy.accepts(Source.PADDLE_ZH_JA, "ねえ", 0.99f))
        assertFalse(ChoicePolicy.accepts(Source.PADDLE_ZH_JA, "상점", 0.99f))
    }

    @Test
    fun 英字専用は英字だけの行を採る() {
        assertTrue(ChoicePolicy.accepts(Source.MLKIT_LATIN, "HAVE FUN", null))
        assertFalse(ChoicePolicy.accepts(Source.MLKIT_LATIN, "상점 SHOP", null))
    }

    @Test
    fun 縦書きの行は確信度が低ければ韓国語の行に譲る() {
        assertTrue(ChoicePolicy.yieldsToKorean(Reading(Source.PADDLE_ZH_JA, "째요", 0.6f)))
        assertFalse(ChoicePolicy.yieldsToKorean(Reading(Source.PADDLE_ZH_JA, "这是", 0.99f)))
        assertTrue(ChoicePolicy.isKoreanLine(Reading(Source.PADDLE_KO, "상점에", 0.9f)))
        assertFalse(ChoicePolicy.isKoreanLine(Reading(Source.MLKIT_KO, "상", null)))
        assertFalse(ChoicePolicy.isKoreanLine(Reading(Source.MLKIT_ZH, "상점에", null)))
    }

    @Test
    fun 表の文字種ごとの読み手は得意な文字種がそろう() {
        // 表に書いた読み手が、その文字種を得意とする読み手であること（読み手を足したときの書き間違いを防ぐ）
        for ((script, sources) in ChoicePolicy.priority) {
            for (s in sources) assertEquals("$script の $s", script, s.specialty)
        }
    }

    @Test
    fun 読み手はどれも採る方針を持つ() {
        // accepts の when に足し忘れるとコンパイルで止まるが、表（priority）への足し忘れはここで止める
        val inTable = ChoicePolicy.priority.values.flatten().toSet()
        val vertical = Source.entries.filter { it.readsVertical }.toSet()
        assertEquals(Source.entries.toSet(), inTable + vertical)
    }
}
