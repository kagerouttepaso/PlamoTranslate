package app.plamotranslate.ocr

import org.junit.Assert.assertEquals
import org.junit.Test

/** 同じ行の読みから1つ選ぶ規則（[LineChoice]、docs/設計/画面翻訳.md「行の選び方とまとめ方」）。 */
class LineChoiceTest {
    private fun choose(vararg readings: Pair<Source, String>): Source =
        LineChoice.choose(readings.map { (s, t) -> Reading(s, t) }).source

    @Test
    fun 縦書きを読める読み手の行はそのまま採る() {
        assertEquals(Source.PADDLE_ZH_JA, choose(Source.PADDLE_ZH_JA to "你在干什么", Source.MLKIT_ZH to "你在干什么"))
    }

    @Test
    fun 英語の行は英字専用の読み手を採る() {
        assertEquals(
            Source.MLKIT_LATIN,
            choose(Source.MLKIT_ZH to "HAVE FUN", Source.MLKIT_JA to "HAVE FUN", Source.MLKIT_KO to "HAVE FUN", Source.MLKIT_LATIN to "HAVE FUN"),
        )
    }

    @Test
    fun 英字専用の読み手が無ければラテン文字を最も多く読めたものを採る() {
        assertEquals(Source.MLKIT_JA, choose(Source.MLKIT_ZH to "HAVE", Source.MLKIT_JA to "HAVE FUN"))
    }

    @Test
    fun 仮名が2字以上でハングル以上なら日本語() {
        assertEquals(Source.MLKIT_JA, choose(Source.MLKIT_ZH to "这是", Source.MLKIT_JA to "これは何", Source.MLKIT_KO to "그"))
    }

    @Test
    fun ハングルが漢字より多ければ韓国語() {
        assertEquals(Source.MLKIT_KO, choose(Source.MLKIT_ZH to "上", Source.MLKIT_JA to "上", Source.MLKIT_KO to "상점에 가자"))
    }

    @Test
    fun 漢字がハングル以上なら中国語() {
        // 縦書きの中国語を韓国語の認識器がハングルに読み違える（「째요또터」）
        assertEquals(Source.MLKIT_ZH, choose(Source.MLKIT_ZH to "这是什么东西", Source.MLKIT_KO to "째요또터"))
    }

    @Test
    fun 崩れた読みは飛ばす() {
        assertEquals(
            Source.MLKIT_ZH,
            choose(Source.MLKIT_ZH to "上上", Source.MLKIT_KO to "Otohi그 7>지가igr<그DE가 abc그 x지"),
        )
    }

    @Test
    fun どれも崩れていれば中国語の読みを返す() {
        assertEquals(Source.MLKIT_ZH, choose(Source.MLKIT_ZH to "<>#{}", Source.MLKIT_JA to "<>#{}"))
    }
}
