package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 区切りの状態遷移（[SpeechSegmenter]）。音声の値で「何語を話しているか」を表し、偽の VAD と偽の認識で区切りを確かめる。
 * 偽の認識は、0.1秒ごとに1文字（日本語は「あ」、韓国語は「가」、中国語は「中」）を返し、言語は多い方にする。
 * 認識の担当の代わり（[Harness]）は、結果をその場で返すか、決めた分だけ遅れて返す。
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

    /**
     * 区切りと、認識の担当の代わり。仕事は待ち行列（[RecognitionQueue]）から取ってその場で処理し（言語の判定を先に）、
     * 書き起こしの結果は [latencyChunks] 個の音声（0.1秒ずつ）を渡した後に返す（0ならその場で返す）。言語の判定は速いので、
     * その場で返す（端末では識別の担当が別のスレッドで、0.3秒ほどで返す）。
     */
    private class Harness(
        recognizer: SpeechRecognizer,
        profile: SegmentationProfile,
        heavy: Boolean,
        languages: SpeechLanguages,
        private val latencyChunks: Int = 0,
        /** 言語の判定（[RecognitionJob.Language]・[RecognitionJob.Seam]）の結果を返すまでの音声の個数。 */
        private val languageLatencyChunks: Int = 0,
    ) {
        val events = mutableListOf<SpeechEvent>()

        /** 頼まれた言語の判定の目的（`直近` など）。 */
        val languagePurposes = mutableListOf<String>()
        private val queue = RecognitionQueue()
        private val runner = RecognitionRunner(recognizer)
        private val waiting = ArrayDeque<Triple<Int, Long, RecognitionOutput>>()
        private var chunks = 0
        val segmenter = SpeechSegmenter(
            FakeVad((profile.phraseSilenceSeconds * 10).toInt()),
            recognizer.identifies,
            profile,
            heavy,
            emit = { events += it },
            submit = {
                if (it is RecognitionJob.Language) languagePurposes += it.purpose
                queue.submit(it)
            },
            trace = { if (System.getenv("SEGMENTER_TRACE") != null) println("[$chunks] ${it()}") },
            // 時計は渡した音声の長さで進める（書き起こしの遅れを、端末と同じく途中結果の間隔に効かせる）
            clock = { chunks * 100_000_000L },
        ).apply { this.languages = languages }

        private fun run(all: Boolean) {
            while (true) {
                while (true) {
                    val job = queue.take(RecognitionLane.IDENTIFY) ?: queue.take(RecognitionLane.TRANSCRIBE) ?: break
                    val delay = if (job is RecognitionJob.Transcribe) latencyChunks else languageLatencyChunks
                    waiting.addLast(Triple(chunks + delay, job.id, runner.run(job)))
                }
                val due = waiting.filter { all || it.first <= chunks }.minByOrNull { it.first } ?: return
                waiting.remove(due)
                segmenter.onResult(due.second, due.third)
            }
        }

        fun accept(samples: FloatArray) {
            segmenter.accept(samples)
            chunks++
            run(all = false)
        }

        /** 頼んだ仕事をすべて終わらせる。 */
        fun settle() = run(all = true)

        fun collect(block: Harness.() -> Unit): List<SpeechEvent> {
            val before = events.size
            block()
            return events.drop(before)
        }

        fun cut() = collect {
            segmenter.cut()
            settle()
        }

        fun flush() = collect {
            segmenter.flush()
            settle()
        }
    }

    private fun segmenter(languages: SpeechLanguages = SpeechLanguages(setOf("ja", "ko")), latency: Int = 0) =
        Harness(FakeRecognizer(::soundOf), profile, heavy = false, languages = languages, latencyChunks = latency)

    /** [parts] の音を0.1秒ずつ流し、出来事を集める（最後に、頼んだ仕事を終わらせる）。 */
    private fun Harness.feed(vararg parts: Pair<Sound, Double>): List<SpeechEvent> = collect {
        parts.forEach { (sound, seconds) -> repeat((seconds * 10).toInt()) { accept(FloatArray(FRAME) { sound.value }) } }
        settle()
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
        // 文の区切り（0.6秒）より長く、話し終わり（3秒）より短い間
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
        // 長さは話し始めから数える（前に足した音声は含めない）。文の切れ目が無ければ直近4秒の静かなところで切る
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
    fun 止めたら話しかけの分も確定し最後に止め終えたと知らせる() {
        val s = segmenter()
        s.feed(Sound.JA to 1.5)
        val events = s.flush()
        assertEquals(1, finals(events).size)
        assertEquals(listOf(SpeechEvent.Speaking(false), SpeechEvent.Flushed), events.takeLast(2))
        // 話していなければ、止め終えた知らせだけ
        assertEquals(listOf(SpeechEvent.Flushed), s.flush())
    }

    @Test
    fun 手で区切ったら話した分を確定し続けた話は次のカードにする() {
        val s = segmenter()
        s.feed(Sound.JA to 1.5)
        val events = s.cut()
        val cut = finals(events)
        assertEquals(1, cut.size)
        assertTrue(cut[0].text.all { it == 'あ' })
        assertEquals(SpeechEvent.CutDone, events.last())
        // 聞くのは続けるので、続けて話した分は次のカードになり、話し終わりで確定する
        val rest = finals(s.feed(Sound.KO to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ko"), rest.map { it.lang })
        // 話していないときに押したら、区切りを終えた知らせだけ
        assertEquals(listOf(SpeechEvent.CutDone), s.cut())
    }

    @Test
    fun 途中結果は同じ文字を続けて出さない() {
        val partials = segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.5).filterIsInstance<SpeechEvent.Partial>()
        assertTrue(partials.isNotEmpty())
        assertEquals(partials.size, partials.map { it.text }.distinct().size)
    }

    @Test
    fun 長く話し続けても文の切れ目があればそこで区切る() {
        // 偽の認識に、10秒目に文の終わりを返させる（区切りの長さの直前6秒の中）
        val recognizer = object : SpeechRecognizer {
            val inner = FakeRecognizer(::soundOf)
            override fun recognize(samples: FloatArray): Recognition {
                val r = inner.recognize(samples)
                return if (samples.size > SAMPLE_RATE * 11) r.copy(breaks = listOf(SpeechBreak(SAMPLE_RATE * 10, sentence = true))) else r
            }
            override fun close() = Unit
        }
        val s = Harness(recognizer, profile, heavy = false, languages = SpeechLanguages(setOf("ja", "ko")))
        val cards = finals(s.feed(Sound.JA to 20.0, Sound.SILENCE to 4.0))
        assertTrue(cards.size >= 2)
        assertTrue("${cards[0].durationMs}", cards[0].durationMs in 9_800L..10_600L)
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
        s.segmenter.reset()
        assertTrue(finals(s.feed(Sound.SILENCE to 3.0)).isEmpty())
        assertTrue(finals(s.flush()).isEmpty())
    }

    @Test
    fun 閉じた後の呼び出しは何もしない() {
        val s = segmenter()
        s.segmenter.close()
        assertTrue(s.feed(Sound.JA to 1.0).isEmpty())
        assertTrue(s.flush().isEmpty())
    }

    @Test
    fun 認識が遅れても出来事はカードの順に出て音声の区切りは遅れない() {
        // 認識の結果が1.5秒遅れて届く。前のカードの確定より先に、次のカードの出来事を出さない
        val events = segmenter(latency = 15).feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.KO to 1.5, Sound.SILENCE to 3.0)
        assertEquals(listOf("ja", "ko"), finals(events).map { it.lang })
        val firstFinal = events.indexOfFirst { it is SpeechEvent.Final }
        assertTrue(events.take(firstFinal).filterIsInstance<SpeechEvent.Partial>().none { it.text.contains('가') })
        // 区切っても音声は欠けない
        assertEquals(15, finals(events)[1].text.length)
    }

    /**
     * 書き起こしの重いモデル（Qwen3-ASR）の代わり。書き起こさずに点数を出せ（[SpeechRecognizer.scores]。決めた言語を点数1にする）、
     * 書き起こした音声の長さ・識別の回数・言語を指定した書き起こしの回数を覚える。
     */
    private inner class HeavyRecognizer(
        /** 識別の結果を変える（受け取った音声の長さと、本来の判定から決める）。 */
        private val identifyAs: (samples: Int, lang: String?) -> String? = { _, lang -> lang },
    ) : SpeechRecognizer {
        private val inner = FakeRecognizer(::soundOf)
        val transcribed = mutableListOf<Int>()
        var identified = 0
        val identifiedWindows = mutableListOf<Int>()

        /** 言語を指定せずに（書き起こしの中で識別して）書き起こした回数。 */
        var unspecified = 0
        override fun recognize(samples: FloatArray): Recognition {
            transcribed += samples.size
            return inner.recognize(samples)
        }

        /** 言語を指定せずに書き起こしを頼まれたときの、識別できなかったときの言語（null なら言語の無い書き起こしになる）。 */
        val fallbacks = mutableListOf<String?>()

        override fun recognize(samples: FloatArray, languages: SpeechLanguages, fallback: String?): Recognition {
            unspecified++
            fallbacks += fallback
            return recognize(samples)
        }

        override fun recognizeAs(samples: FloatArray, language: String?): Recognition = recognize(samples).copy(lang = language)

        override val identifies get() = true
        override fun scores(samples: FloatArray, languages: SpeechLanguages): Map<String, Float>? {
            identified++
            identifiedWindows += samples.size
            val lang = inner.recognize(samples).lang?.takeIf { languages.groups == null || it in languages.groups }
            return identifyAs(samples.size, lang)?.let { mapOf(it to 1f) }
        }

        override fun close() = Unit
    }

    private fun phraseSegmenter(asr: SpeechRecognizer, latency: Int = 0) =
        Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")), latencyChunks = latency)

    @Test
    fun 重いモデルは文ごとに書き起こし確定でカード全体を書き起こし直さない() {
        val asr = HeavyRecognizer()
        val events = phraseSegmenter(asr).feed(Sound.JA to 2.5, Sound.SILENCE to 1.0, Sound.JA to 2.5, Sound.SILENCE to 3.0)
        val card = finals(events).single()
        assertEquals("ja", card.lang)
        assertEquals(50, card.text.length)
        assertTrue(card.text, card.text.all { it == 'あ' })
        // カードは6秒あるが、1回に書き起こすのは1つの文（文の前の間と、文の終わりを見つけるまでの無音を入れても4秒未満）だけ
        assertTrue("${asr.transcribed}", asr.transcribed.max() < SAMPLE_RATE * 4)
        // 言語の見張りは識別だけで行い、カードの言語が決まった後の書き起こしは言語を指定する（識別を繰り返さない）
        assertTrue(asr.identified > 0)
        assertEquals(0, asr.unspecified)
    }

    @Test
    fun 重いモデルには話し始めの前の無音を渡さず言葉は欠かさない() {
        val asr = HeavyRecognizer()
        val card = finals(phraseSegmenter(asr).feed(Sound.SILENCE to 2.0, Sound.JA to 2.5, Sound.SILENCE to 3.0)).single()
        assertEquals(25, card.text.length)
        // 前に足した1.5秒のうち、声の0.3秒前から渡す。最初の書き起こし（カードの言語が決まった2秒の時点）は0.3秒＋2秒
        assertTrue("${asr.transcribed}", asr.transcribed.first() <= SAMPLE_RATE * 24 / 10)
    }

    @Test
    fun 重いモデルは文字より先にカードの言語を知らせる() {
        val partials = phraseSegmenter(HeavyRecognizer()).feed(Sound.KO to 2.5, Sound.SILENCE to 3.0)
            .filterIsInstance<SpeechEvent.Partial>()
        assertEquals(SpeechEvent.Partial("", "ko"), partials.first())
        assertTrue(partials.drop(1).any { it.text.isNotEmpty() })
    }

    @Test
    fun カードの言語を決める長さを短くすると早く文字が出る() {
        fun firstTextAt(seconds: Float): Int {
            val s = Harness(
                HeavyRecognizer(), profile.copy(cardLanguageSeconds = seconds), heavy = true, languages = SpeechLanguages(setOf("ja", "ko")),
            )
            val events = s.feed(Sound.KO to 3.0)
            return events.indexOfFirst { it is SpeechEvent.Partial && it.text.isNotEmpty() }.also { assertTrue(it >= 0) }.let {
                (events[it] as SpeechEvent.Partial).text.length
            }
        }
        // 最初の文字が出た時点の文字数（話し始めからの長さ）が短い
        assertTrue(firstTextAt(1.0f) < firstTextAt(2.0f))
    }

    @Test
    fun 重いモデルでも話し手の交代で区切る() {
        val gap = finals(phraseSegmenter(HeavyRecognizer()).feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.KO to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), gap.map { it.lang })
        assertTrue(gap[1].text.all { it == '가' })
        val live = finals(phraseSegmenter(HeavyRecognizer()).feed(Sound.JA to 3.0, Sound.KO to 4.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), live.map { it.lang })
    }

    @Test
    fun カードの言語を判定できなければ同じ窓を繰り返さず広い窓で判定し直す() {
        // 3秒より短い窓は判定できない（会話の外の言語と判定する）識別
        val asr = HeavyRecognizer { samples, lang -> lang.takeIf { samples >= SAMPLE_RATE * 3 } }
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko"), primary = "ja"))
        val events = s.feed(Sound.KO to 4.0, Sound.SILENCE to 3.0)
        val head = asr.identifiedWindows.filter { it >= SAMPLE_RATE * 2 }
        // 同じ長さの窓を何度も頼まない
        assertEquals(head.distinct().size, head.size)
        assertEquals("ko", finals(events).single().lang)
    }

    @Test
    fun 一番広い窓でも判定できなければ自分の言語として書き起こす() {
        val asr = HeavyRecognizer { _, _ -> null }
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko"), primary = "ja"))
        // 最初の文はカードの言語が決まる前に終わる（言語を指定せずに書き起こしを頼む）
        val events = s.feed(Sound.JA to 2.5, Sound.SILENCE to 1.0, Sound.JA to 5.0, Sound.SILENCE to 3.0)
        // 文字は話し終わる前に出る（言語が決まらないまま待たない）
        assertTrue(events.takeWhile { it !is SpeechEvent.Final }.any { it is SpeechEvent.Partial && it.text.isNotEmpty() })
        assertEquals("ja", finals(events).single().lang)
        // 言語の手がかりが無くても、言語を決めずに書き起こさない（会話に無い言語の文を作り出すため）
        assertEquals(0, asr.unspecified)
    }

    @Test
    fun 話し中の窓で判定できなくても文の全体で話し手を決めて区切る() {
        // 2.5秒より短い窓は判定できない識別。韓国語の文の後、間を空けて日本語の文（3秒）
        val asr = HeavyRecognizer { samples, lang -> lang.takeIf { samples >= SAMPLE_RATE * 5 / 2 } }
        val cards = finals(phraseSegmenter(asr).feed(Sound.KO to 3.0, Sound.SILENCE to 1.0, Sound.JA to 3.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ko", "ja"), cards.map { it.lang })
        assertTrue(cards[1].text, cards[1].text.all { it == 'あ' })
    }

    @Test
    fun 話し中の見張りは判定できなかった窓で数え直さない() {
        // 2回に1回は判定できない識別でも、韓国語が続けば日本語のカードを区切る
        var calls = 0
        val asr = HeavyRecognizer { _, lang -> lang.takeIf { calls++ % 2 == 0 } }
        val cards = finals(phraseSegmenter(asr).feed(Sound.JA to 3.0, Sound.KO to 6.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
    }

    @Test
    fun 書き起こしが重く途中結果の間隔が延びても話し中の交代にすぐ気づき継ぎ目で区切る() {
        // 書き起こしに1.5秒かかる（途中結果の間隔は3秒に延びる）。言語の識別はその場で返る
        val cards = finals(phraseSegmenter(HeavyRecognizer(), latency = 15).feed(Sound.JA to 4.0, Sound.KO to 5.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
        // 区切りは言語の継ぎ目の近く：韓国語の頭が前のカードに残らない
        assertTrue(cards[0].text, cards[0].text.count { it == '가' } <= 3)
        assertTrue(cards[1].text, cards[1].text.count { it == '가' } >= 45)
    }

    @Test
    fun 言語の判定が遅いときは話し中の見張りの間隔を延ばして担当の時間を残す() {
        // 言語を自分で識別するモデル（SenseVoice）では、見張りは書き起こしの担当で動く
        fun watches(languageLatency: Int): Int {
            val s = Harness(FakeRecognizer(::soundOf), profile, heavy = false, languages = SpeechLanguages(setOf("ja", "ko")),
                languageLatencyChunks = languageLatency)
            s.feed(Sound.JA to 10.0, Sound.SILENCE to 3.0)
            return s.languagePurposes.count { it == "話し中の窓" }
        }
        val fast = watches(0)
        val slow = watches(5)
        assertTrue("速い $fast 遅い $slow", slow * 2 <= fast)
    }

    @Test
    fun 文の判定が遅れて届いても文の頭で区切る() {
        // 点数は1.2秒遅れて届くので、判定より先に次の文が始まる
        val asr = HeavyRecognizer()
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")), languageLatencyChunks = 12)
        val cards = finals(s.feed(Sound.KO to 3.0, Sound.SILENCE to 1.0, Sound.JA to 1.8, Sound.SILENCE to 4.0))
        assertEquals(listOf("ko", "ja"), cards.map { it.lang })
    }

    @Test
    fun 話し始めの窓が誤っても話し手が確かになる前は区切らず仮の言語を替える() {
        // 話し始めの2.5秒だけ、日本語を韓国語と判定する識別（PC で、日本語の頭の窓を韓国語 0.95 と判定した）。
        // 文の全体（3.5秒より長い）では正しく判定する
        val asr = HeavyRecognizer { samples, lang -> if (samples <= SAMPLE_RATE * 5 / 2 && lang == "ja") "ko" else lang }
        val events = phraseSegmenter(asr).feed(Sound.JA to 6.0, Sound.SILENCE to 3.0)
        val card = finals(events).single()
        assertEquals("ja", card.lang)
        assertTrue(card.text, card.text.all { it == 'あ' })
    }

    @Test
    fun 継ぎ目の後ろが新しい言語と決まらなければ話し中に区切らない() {
        // 窓（1.5秒以下）だけ韓国語と誤り、長い音声では日本語と判定する識別。カードは文の判定で日本語と確か
        val asr = HeavyRecognizer { samples, lang -> if (samples <= SAMPLE_RATE * 3 / 2 && lang == "ja") "ko" else lang }
        val s = phraseSegmenter(asr)
        val cards = finals(s.feed(Sound.JA to 3.0, Sound.SILENCE to 1.0, Sound.JA to 6.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja"), cards.map { it.lang })
    }

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

    @Test
    fun 重いモデルで認識が遅れても確定は欠けずカードの順に出る() {
        val events = phraseSegmenter(HeavyRecognizer(), latency = 20)
            .feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.KO to 2.5, Sound.SILENCE to 3.0)
        val cards = finals(events)
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
        assertEquals(listOf(15, 25), cards.map { it.text.length })
    }

    @Test
    fun 重いモデルで文の途中を手で区切ると残りだけ書き起こす() {
        val s = phraseSegmenter(HeavyRecognizer())
        s.feed(Sound.JA to 1.5, Sound.SILENCE to 1.0, Sound.JA to 1.0)
        val card = finals(s.cut()).single()
        assertEquals(25, card.text.length)
        assertTrue(card.text, card.text.all { it == 'あ' })
    }

    @Test
    fun 書き起こしは文字の種類でつなぎ方を変える() {
        assertEquals("Hello world", TranscriptJoin.join("Hello", "world"))
        assertEquals("こんにちは。元気です", TranscriptJoin.join("こんにちは。", "元気です"))
        assertEquals("Привет мир", TranscriptJoin.join("Привет", "мир"))
        assertEquals("ok", TranscriptJoin.stitch(listOf(Recognition("", "ja"), Recognition("ok", "en"))).text)
    }

    @Test
    fun 認識の待ち行列は確定に要る書き起こしを途中結果より先に出す() {
        val q = RecognitionQueue()
        fun t(id: Long, card: Long, p: RecognitionPriority) =
            RecognitionJob.Transcribe(id, card, p, FloatArray(0), 0, "", SpeechLanguages.ANY, null, null)
        q.submit(t(0, 0, RecognitionPriority.PARTIAL))
        q.submit(t(1, 0, RecognitionPriority.PHRASE))
        q.submit(t(2, 0, RecognitionPriority.FINAL))
        q.submit(t(3, 0, RecognitionPriority.PHRASE))
        assertTrue(q.hasUrgent())
        assertEquals(listOf(2L, 1L, 3L, 0L), List(4) { q.take(RecognitionLane.TRANSCRIBE)!!.id })
        // 閉じたカードの途中結果は捨て、確定に要るものは残す
        q.submit(t(4, 0, RecognitionPriority.PARTIAL))
        q.submit(t(5, 0, RecognitionPriority.PHRASE))
        q.dropStale(openCard = 1)
        assertEquals(5L, q.take(RecognitionLane.TRANSCRIBE)!!.id)
        assertEquals(null, q.take(RecognitionLane.TRANSCRIBE))
    }

    @Test
    fun 声の始まりは前に足した区間の部屋の音との比で見つける() {
        val quiet = FloatArray(SAMPLE_RATE) { if (it % 2 == 0) 0.001f else -0.001f }
        val voice = FloatArray(SAMPLE_RATE / 2) { if (it % 2 == 0) 0.05f else -0.05f }
        val audio = quiet + voice
        // 声は1.0秒から。その0.3秒前から渡す
        assertEquals(SAMPLE_RATE - SpeechOnset.MARGIN, SpeechOnset.start(audio, audio.size))
        // 声が見つからなければ、VAD が見つけた位置の0.3秒前
        assertEquals(SAMPLE_RATE - SpeechOnset.MARGIN, SpeechOnset.start(quiet, quiet.size))
    }

    companion object {
        private const val FRAME = SAMPLE_RATE / 10
    }
}
