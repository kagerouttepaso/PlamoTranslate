package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区切りの状態遷移（[SpeechSegmenter]）。音声の値で「何語を話しているか」を表し、偽の VAD と偽の認識で区切りを確かめる。
 * 偽の認識は、0.1秒ごとに1文字（日本語は「あ」、韓国語は「가」、中国語は「中」）を返し、言語は多い方にする。
 */
class SpeechSegmenterTest {
    private enum class Sound(val value: Float, val char: String?, val lang: String?) {
        SILENCE(0f, null, null),
        JA(0.1f, "あ", "ja"),
        KO(0.2f, "가", "ko"),
        ZH(0.3f, "中", "zh"),

        /** 英語。1区間ごとに1語。 */
        EN(0.5f, " word", "en"),

        /** 小さい声の日本語。VAD は話していないとみなすが、認識器は聞き取る。 */
        SOFT(0.05f, "ぁ", "ja"),

        /** 雑音。認識器が「Yeah.」と聞き違える。 */
        NOISE(0.4f, null, "en"),
    }

    private fun soundOf(v: Float) = Sound.entries.minBy { kotlin.math.abs(it.value - v) }

    /** 0.1秒ごとに音の種類を見て、文字と言語を返す偽の認識。 */
    private class FakeRecognizer(private val soundOf: (Float) -> Sound) : SpeechRecognizer {
        override fun recognize(samples: FloatArray): Recognition {
            val frames = samples.toList().chunked(FRAME).map { f -> soundOf(f.groupingBy { it }.eachCount().maxBy { it.value }.key) }
            val speech = frames.filter { it != Sound.SILENCE }
            if (speech.isEmpty()) return Recognition("", null)
            if (speech.all { it == Sound.NOISE }) return Recognition("Yeah.", "en")
            val lang = speech.filter { it != Sound.NOISE }.groupingBy { it }.eachCount().maxBy { it.value }.key
            return Recognition(speech.mapNotNull { it.char }.joinToString("").trim(), lang.lang)
        }

        override fun close() = Unit
    }

    /** 小さい声より大きい音があれば話している、無音が文の区切りの長さ続いたら文の終わり、とする偽の VAD（検出の遅れは無い）。 */
    private class FakeVad(private val phraseSilenceChunks: Int) : VoiceActivity {
        private var inSpeech = false
        private var silent = 0
        override fun accept(samples: FloatArray): VoiceSignal {
            val speaking = samples.any { it >= 0.08f }
            var ended = false
            if (speaking) {
                inSpeech = true
                silent = 0
            } else if (inSpeech && ++silent >= phraseSilenceChunks) {
                inSpeech = false
                ended = true
            }
            return VoiceSignal(inSpeech, ended)
        }

        override fun flush() = Unit
        override fun reset() {
            inSpeech = false
            silent = 0
        }

        override fun close() = Unit
    }

    private val profile = SegmentationProfile.CONVERSATION

    private fun segmenter(languages: SpeechLanguages = SpeechLanguages(setOf("ja", "ko"))) = SpeechSegmenter(
        FakeVad((profile.phraseSilenceSeconds * 10).toInt()),
        FakeRecognizer(::soundOf),
        profile,
    ).apply { this.languages = languages }

    /** [parts] の音を0.1秒ずつ流し、出来事を集める。 */
    private fun SpeechSegmenter.feed(vararg parts: Pair<Sound, Double>): List<SpeechEvent> =
        parts.flatMap { (sound, seconds) ->
            (0 until (seconds * 10).toInt()).flatMap { accept(FloatArray(FRAME) { sound.value }) }
        }

    private fun finals(events: List<SpeechEvent>) = events.filterIsInstance<SpeechEvent.Final>().map { it.utterance }

    @Test
    fun 話し終わりの無音で1枚のカードを確定する() {
        val events = segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 3.0)
        val cards = finals(events)
        assertEquals(1, cards.size)
        assertEquals("ja", cards[0].lang)
        assertTrue(cards[0].text.all { it == 'あ' })
        assertEquals(SpeechEvent.Speaking(true), events.first())
        assertEquals(SpeechEvent.Speaking(false), events.last())
    }

    @Test
    fun 言いよどみの無音では区切らない() {
        // 文の区切り（0.6秒）より長く、話し終わり（2秒）より短い間
        val cards = finals(segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.JA to 1.5, Sound.SILENCE to 3.0))
        assertEquals(1, cards.size)
    }

    @Test
    fun 間を空けて違う言語で話し始めたら前の文までで区切る() {
        val cards = finals(segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.KO to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
        assertTrue(cards[1].text.all { it == '가' })
    }

    @Test
    fun 間を空けずに言語が代わっても話している最中に区切る() {
        val cards = finals(segmenter().feed(Sound.JA to 4.0, Sound.KO to 4.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
        // 区切りは言語の継ぎ目の近く：前のカードはほとんど日本語、後ろはほとんど韓国語
        assertTrue(cards[0].text.count { it == 'あ' } >= 35)
        assertTrue(cards[1].text.count { it == '가' } >= 35)
    }

    @Test
    fun 長く話し続けたら区切り後ろは次のカードの頭に残す() {
        val cards = finals(segmenter().feed(Sound.JA to 20.0, Sound.SILENCE to 3.0))
        assertEquals(2, cards.size)
        // 長さは話し始めから数える（前に足した音声は含めない）
        assertTrue(cards[0].durationMs <= ((profile.splitAfterSeconds + profile.prerollSeconds) * 1000).toLong())
        assertTrue(cards[0].durationMs > ((profile.splitAfterSeconds - 4) * 1000).toLong())
        // 区切っても音声は欠けない（合わせて20秒分の文字。区切りが0.1秒の途中に入ると、偽の認識は両側で1文字ずつ数える）
        assertTrue(cards.sumOf { it.text.length } in 200..201)
    }

    @Test
    fun 雑音の聞き違いはカードにも話し手の交代にも使わない() {
        val events = segmenter().feed(Sound.NOISE to 1.0, Sound.SILENCE to 3.0)
        assertTrue(finals(events).isEmpty())
        val cards = finals(
            segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.NOISE to 1.2, Sound.SILENCE to 1.0, Sound.JA to 1.5, Sound.SILENCE to 3.0),
        )
        assertEquals(1, cards.size)
    }

    @Test
    fun 中国語の相手でなければ中国語の判定は日本語と同じ話し手とみなす() {
        val sameSpeaker = finals(segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.ZH to 1.5, Sound.SILENCE to 3.0))
        assertEquals(1, sameSpeaker.size)
        val chinese = segmenter(SpeechLanguages(setOf("ja", "zh"), separateChinese = true))
        val cards = finals(chinese.feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.ZH to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "zh"), cards.map { it.lang })
    }

    @Test
    fun 会話の言語でない言語が混ざっても区切らない() {
        val cards = finals(segmenter().feed(Sound.JA to 4.0, Sound.EN to 3.0, Sound.JA to 2.0, Sound.SILENCE to 3.0))
        assertEquals(1, cards.size)
    }

    @Test
    fun 話している最中の交代は続けて違ったときだけ区切る() {
        // 直近の窓が2回だけ違う言語（約1秒）なら区切らない
        val cards = finals(segmenter().feed(Sound.JA to 4.0, Sound.KO to 1.0, Sound.JA to 3.0, Sound.SILENCE to 3.0))
        assertEquals(1, cards.size)
    }

    @Test
    fun 話し終わりの確定は話していないとみなした小さい声の語尾も入れる() {
        val card = finals(segmenter().feed(Sound.JA to 1.5, Sound.SOFT to 0.5, Sound.SILENCE to 3.0)).single()
        assertTrue(card.text, card.text.endsWith("ぁ"))
    }

    @Test
    fun 止めたら話しかけの分も確定する() {
        val s = segmenter()
        s.feed(Sound.JA to 1.5)
        val events = s.flush()
        assertEquals(1, finals(events).size)
        assertEquals(SpeechEvent.Speaking(false), events.last())
    }

    @Test
    fun 手で区切ったら話した分を確定し続けた話は次のカードにする() {
        val s = segmenter()
        s.feed(Sound.JA to 1.5)
        val cut = finals(s.cut())
        assertEquals(1, cut.size)
        assertTrue(cut[0].text.all { it == 'あ' })
        // 聞くのは続けるので、続けて話した分は次のカードになり、話し終わりで確定する
        val rest = finals(s.feed(Sound.KO to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ko"), rest.map { it.lang })
        // 話していないときに押しても何も起きない
        assertTrue(s.cut().isEmpty())
    }

    @Test
    fun 途中結果は同じ文字を続けて出さない() {
        val partials = segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.5).filterIsInstance<SpeechEvent.Partial>()
        assertTrue(partials.isNotEmpty())
        assertEquals(partials.size, partials.map { it.text }.distinct().size)
    }

    @Test
    fun 話し始めの前の音声を頭に足し始まりの時刻は足した分を含めて数える() {
        val cards = finals(segmenter().feed(Sound.SILENCE to 2.0, Sound.JA to 1.5, Sound.SILENCE to 3.0))
        val preroll = (profile.prerollSeconds * 1000).toLong()
        assertEquals(2000 - preroll, cards.single().startMs)
        assertTrue(cards.single().durationMs >= 1500 + preroll)
    }

    @Test
    fun 始め直したら前の話しかけは捨てる() {
        val s = segmenter()
        s.feed(Sound.JA to 1.5)
        s.reset()
        assertTrue(finals(s.feed(Sound.SILENCE to 3.0)).isEmpty())
        assertTrue(s.flush().isEmpty())
    }

    @Test
    fun 閉じた後の呼び出しは何もしない() {
        val s = segmenter()
        s.close()
        assertTrue(s.accept(FloatArray(FRAME) { Sound.JA.value }).isEmpty())
        assertTrue(s.flush().isEmpty())
    }

    companion object {
        private const val FRAME = SAMPLE_RATE / 10
    }
}
