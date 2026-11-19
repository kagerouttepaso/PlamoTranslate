package app.plamotranslate.pipeline.speech

import org.junit.Assert.assertEquals
import org.junit.Test

/** 話し手の判定（[SpeakerJudge]）。会話の言語ごとのスコアから、話し手の言語を決める（決められなければ決めない）。 */
class SpeakerJudgeTest {
    @Test
    fun 自分の言語かそれ以外かで決め相手不明の間は候補の合計で比べる() {
        val unknown = SpeechLanguages(setOf("ja", "ko", "en", "ru"), primary = "ja")
        // 自分以外の合計が多ければ、そのうち一番の言語
        assertEquals("ru", SpeakerJudge.decide(mapOf("ja" to 0.2f, "ko" to 0.2f, "en" to 0.15f, "ru" to 0.45f), unknown, SpeakerJudge.PHRASE))
        assertEquals("ja", SpeakerJudge.decide(mapOf("ja" to 0.8f, "ko" to 0.1f, "en" to 0.05f, "ru" to 0.05f), unknown, SpeakerJudge.PHRASE))
        // 差が小さければ決めない（前の話し手のまま）
        assertEquals(null, SpeakerJudge.decide(mapOf("ja" to 0.5f, "ko" to 0.5f), SpeechLanguages(setOf("ja", "ko"), primary = "ja"), SpeakerJudge.PHRASE))
        assertEquals(null, SpeakerJudge.decide(mapOf("ja" to 0.3f, "ko" to 0.7f), SpeechLanguages(setOf("ja", "ko"), primary = "ja"), SpeakerJudge.WINDOW))
        assertEquals(null, SpeakerJudge.decide(null, unknown, SpeakerJudge.PHRASE))
    }
}
