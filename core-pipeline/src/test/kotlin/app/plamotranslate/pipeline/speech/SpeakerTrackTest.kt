package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeakerTrack.Verdict
import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Test

/** 話し手の時系列（[SpeakerTrack]）の約束。位置は秒で書き、サンプル数に直して渡す。 */
class SpeakerTrackTest {
    private fun s(seconds: Double) = (seconds * SAMPLE_RATE).toLong()

    /** 話し始め（0秒）のカード。違う言語の窓が3回続けば交代とみなす。 */
    private fun track() = SpeakerTrack(start = 0, switchChecks = 3)

    /** [at] 秒で終わる1.5秒の窓。 */
    private fun SpeakerTrack.window(at: Double, lang: String?) = window(s(at - 1.5), lang)

    /** 日本語と確かになったカード。 */
    private fun sureJa() = track().apply {
        window(2.0, "ja")
        window(2.5, "ja")
        window(3.0, "ja")
        assertEquals(true, sure)
    }

    @Test
    fun 最初の窓で仮の言語を決め同じ言語の窓が続けば確かにする() {
        val t = track()
        assertEquals(Verdict.Assign("ja"), t.window(2.0, "ja"))
        assertEquals(false, t.sure)
        t.window(2.5, "ja")
        t.window(3.0, "ja")
        assertEquals("ja", t.lang)
        assertEquals(true, t.sure)
    }

    @Test
    fun 違う言語の窓が続けて決めた回数に届くまで交代にしない() {
        val t = sureJa()
        assertEquals(Verdict.Keep, t.window(3.5, "en"))
        assertEquals(Verdict.Keep, t.window(4.0, "en"))
        // 同じ言語の窓が挟まれば数え直す
        t.window(4.5, "ja")
        assertEquals(Verdict.Keep, t.window(5.0, "en"))
        assertEquals(Verdict.Keep, t.window(5.5, "en"))
        assertEquals("ja", t.lang)
    }

    @Test
    fun 決められなかった窓は交代の根拠にも反証にもしない() {
        val t = sureJa()
        t.window(3.5, "en")
        t.window(4.0, null)
        t.window(4.5, "en")
        t.window(5.0, null)
        assertEquals(Verdict.Seek("en", s(1.5)), t.window(5.5, "en"))
    }

    @Test
    fun 交代に気づくのが遅れても継ぎ目はカードの話し手と同じだった最後の窓から探させる() {
        val t = sureJa()
        t.window(6.0, "en")
        t.window(8.0, "en")
        // 気づくのは10秒の窓だが、探すのは日本語と決まった最後の窓（1.5〜3秒）の頭から
        assertEquals(Verdict.Seek("en", s(1.5)), t.window(10.0, "en"))
    }

    @Test
    fun 継ぎ目が見つからなければ違った窓を数え直す() {
        val t = sureJa()
        t.window(3.5, "en")
        t.window(4.0, "en")
        t.window(4.5, "en")
        t.seamMissed()
        assertEquals(Verdict.Keep, t.window(5.0, "en"))
        assertEquals(Verdict.Keep, t.window(5.5, "en"))
        assertEquals(Verdict.Seek("en", s(1.5)), t.window(6.0, "en"))
    }

    @Test
    fun 仮のカードは全体で確かめ全体も新しい言語なら言語を替え違えば交代として探させる() {
        fun provisional() = track().apply {
            window(2.0, "ko")
            window(2.5, "ja")
            window(3.0, "ja")
            assertEquals(Verdict.Recheck("ja"), window(3.5, "ja"))
        }
        val misheard = provisional()
        assertEquals(Verdict.Assign("ja"), misheard.recheck("ja", judged = "ja"))
        assertEquals(true, misheard.sure)

        val switched = provisional()
        assertEquals(Verdict.Seek("ja", s(0.5)), switched.recheck("ja", judged = "ko"))
        assertEquals("ko", switched.lang)
    }

    @Test
    fun 文の全体が確かな話し手と違えば文の頭で区切らせカードの頭の文なら言語を替える() {
        assertEquals(Verdict.Cut("en", s(4.0)), sureJa().phrase(s(4.0), "en"))
        assertEquals(Verdict.Keep, sureJa().phrase(s(4.0), null))

        // 仮のカードは、文の話し手をカードの話し手にして確かにする
        val provisional = track().apply { window(2.0, "ko") }
        assertEquals(Verdict.Assign("ja"), provisional.phrase(s(4.0), "ja"))
        assertEquals(true, provisional.sure)

        // カードの頭から始まる文は、区切る前が無いので言語を替える
        assertEquals(Verdict.Assign("en"), sureJa().phrase(0, "en"))
    }

    @Test
    fun 閉じた後は区切らせず確かな話し手を決め直さない() {
        val sure = sureJa().apply { close() }
        assertEquals(Verdict.Keep, sure.phrase(s(4.0), "en"))
        assertEquals("ja", sure.lang)

        // まだ確かでなければ、届いた文で決める（言語の無いカードの残り）
        val unknown = track().apply { close() }
        assertEquals(Verdict.Assign("en"), unknown.phrase(s(1.0), "en"))
    }
}
