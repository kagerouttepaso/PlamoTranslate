package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeakerTrack.Verdict
import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Test

/** 話し手の時系列（[SpeakerTrack]）の約束。位置は秒で書き、サンプル数に直して渡す。 */
class SpeakerTrackTest {
    private fun s(seconds: Double) = (seconds * SAMPLE_RATE).toLong()

    /** 話し始め（0秒）のカード。同じ言語の根拠が2秒の音声にわたれば、その話し手が話したとみなす。 */
    private fun track() = SpeakerTrack(start = 0, minTurn = s(2.0))

    /** [at] 秒で終わる1.5秒の窓。 */
    private fun SpeakerTrack.window(at: Double, lang: String?) = window(s(at - 1.5), s(at), lang)

    private fun SpeakerTrack.phrase(from: Double, to: Double, lang: String?) = phrase(s(from), s(to), lang)

    /** 日本語と確かになったカード（0.5〜2.5秒の日本語の根拠。最後の窓の頭は1秒）。 */
    private fun sureJa() = track().apply {
        window(2.0, "ja")
        window(2.5, "ja")
        assertEquals(true, sure)
    }

    @Test
    fun 最初の窓で仮の言語を決め同じ言語の根拠が決めた長さに届けば確かにする() {
        val t = track()
        assertEquals(Verdict.Assign("ja"), t.window(2.0, "ja"))
        assertEquals(false, t.sure)
        t.window(2.5, "ja")
        assertEquals("ja", t.lang)
        assertEquals(true, t.sure)
    }

    @Test
    fun 違う言語の根拠は窓の数ではなく音声の長さで数える() {
        val t = sureJa()
        assertEquals(Verdict.Keep, t.window(4.0, "en"))
        assertEquals(Verdict.Seek("en", s(1.0), s(4.0)), t.window(4.5, "en"))

        // 窓の間隔が空く端末でも、2つの窓が2秒にわたれば同じ
        val slow = sureJa()
        assertEquals(Verdict.Keep, slow.window(4.0, "en"))
        assertEquals(Verdict.Seek("en", s(1.0), s(4.0)), slow.window(6.0, "en"))
    }

    @Test
    fun 決められなかった窓は根拠を伸ばしも切りもしない() {
        val t = sureJa()
        t.window(4.0, "en")
        t.window(4.3, null)
        assertEquals(Verdict.Seek("en", s(1.0), s(4.0)), t.window(4.5, "en"))
    }

    @Test
    fun 同じ言語の窓が挟まれば違う言語の根拠は数え直す() {
        val t = sureJa()
        t.window(4.0, "en")
        t.window(4.3, "ja")
        assertEquals(Verdict.Keep, t.window(5.5, "en"))
        assertEquals(Verdict.Seek("en", s(2.8), s(5.5)), t.window(6.0, "en"))
    }

    @Test
    fun 交代に気づくのが遅れても継ぎ目はカードの話し手と同じだった最後の窓から探させる() {
        val t = sureJa()
        t.window(8.0, "en")
        // 気づくのは10秒の窓だが、探すのは日本語と決まった最後の窓（1〜2.5秒）の頭から、英語の最初の窓の終わり（8秒）まで
        assertEquals(Verdict.Seek("en", s(1.0), s(8.0)), t.window(10.0, "en"))
        // 探している間は、同じ交代で探させ直さない
        assertEquals(Verdict.Keep, t.window(10.5, "en"))
    }

    @Test
    fun 継ぎ目が見つからなければその根拠を使い終え次の窓から数え直す() {
        val t = sureJa()
        t.window(4.0, "en")
        t.window(4.5, "en")
        t.seamMissed()
        assertEquals(Verdict.Keep, t.window(5.0, "en"))
        assertEquals(Verdict.Seek("en", s(1.0), s(5.0)), t.window(6.0, "en"))
    }

    @Test
    fun 継ぎ目が見つからなかった根拠は文の全体の判定を止めない() {
        val t = sureJa().apply { window(4.0, "en"); window(4.5, "en"); seamMissed() }
        assertEquals(Verdict.Cut("en", s(3.0)), t.phrase(3.0, 5.0, "en"))
    }

    @Test
    fun 重なった窓は覆った音声を1回だけ数える() {
        val t = sureJa()
        t.window(4.0, "en")
        // 2.7〜4.2秒の窓は、2.5〜4.0秒の窓と重なるので、覆った音声は1.7秒
        assertEquals(Verdict.Keep, t.window(4.2, "en"))
    }

    @Test
    fun 仮の言語と違う根拠が決めた長さに届けば仮の言語が誤りだったとして替える() {
        val t = track().apply { window(2.0, "ko") }
        assertEquals(Verdict.Keep, t.window(3.0, "ja"))
        assertEquals(Verdict.Assign("ja"), t.window(3.5, "ja"))
        assertEquals(true, t.sure)
    }

    @Test
    fun 文の中で窓が話し手を決めていれば文の全体の多数決で上書きしない() {
        // 日本語のカードで英語が2秒以上続いた文：文の全体が日本語でも、英語の交代は継ぎ目探しに任せる
        val switched = sureJa().apply { window(4.0, "en"); window(4.5, "en") }
        assertEquals(Verdict.Keep, switched.phrase(0.0, 5.0, "ja"))
        // 日本語が2秒以上続いた文を、文の全体が英語としても区切らない
        assertEquals(Verdict.Keep, sureJa().phrase(0.0, 3.0, "en"))
        assertEquals("ja", switched.lang)
    }

    @Test
    fun 窓の根拠が無い文は文の全体で決め確かな話し手と違えば文の頭で区切らせる() {
        assertEquals(Verdict.Cut("en", s(4.0)), sureJa().phrase(4.0, 5.5, "en"))
        assertEquals(Verdict.Keep, sureJa().phrase(4.0, 5.5, null))

        // 仮のカードは、文の話し手をカードの話し手にして確かにする
        val provisional = track().apply { window(2.0, "ko") }
        assertEquals(Verdict.Assign("ja"), provisional.phrase(0.0, 2.0, "ja"))
        assertEquals(true, provisional.sure)

        // カードの頭から始まる文は、区切る前が無いので言語を替える
        val head = track().apply { assign("ja", sure = true) }
        assertEquals(Verdict.Assign("en"), head.phrase(0.0, 1.5, "en"))
    }

    @Test
    fun 閉じた後は区切らせず確かな話し手を決め直さない() {
        val sure = sureJa().apply { close() }
        assertEquals(Verdict.Keep, sure.phrase(4.0, 5.5, "en"))
        assertEquals("ja", sure.lang)

        // まだ確かでなければ、届いた文で決める（言語の無いカードの残り）
        val unknown = track().apply { close() }
        assertEquals(Verdict.Assign("en"), unknown.phrase(0.0, 1.0, "en"))
    }
}
