package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * セグメンター（[SpeechSegmenter]）の状態遷移。音声の値で「何語を話しているか」を表し、偽の VAD と偽の認識で区切りを確かめる。
 * 偽の認識は、0.1秒ごとに1文字（日本語は「あ」、韓国語は「가」、中国語は「中」）を返し、言語は多い方にする。
 * 認識のワーカーの代わり（[Harness]）は、結果をその場で返すか、決めた分だけ遅れて返す。
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

    @Test
    fun 指定された言語は識別を待たずに途中と確定を書き起こす() {
        val requested = mutableListOf<String?>()
        val recognizer = object : SpeechRecognizer {
            override fun recognize(samples: FloatArray): Recognition = error("識別せずに言語を指定する")
            override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
                requested += language
                return Recognition("A sample speech.", null)
            }
            override fun close() = Unit
        }
        val speech = Harness(recognizer, profile, heavy = true, languages = SpeechLanguages(specified = "en"))
        repeat(30) { speech.accept(FloatArray(FRAME) { Sound.EN.value }) }
        assertTrue(speech.events.filterIsInstance<SpeechEvent.CardText>().any { it.text.isNotEmpty() && it.lang == "en" })
        speech.flush()
        assertEquals("en", speech.events.filterIsInstance<SpeechEvent.CardFinished>().single().utterance?.lang)
        assertTrue(requested.size >= 2)
        assertTrue(requested.all { it == "en" })
        assertTrue(speech.languagePurposes.isEmpty())
    }

    @Test
    fun 候補が中国語の一組でも利用者の指定が無ければ識別する() {
        val speech = Harness(
            FakeRecognizer(::soundOf), profile, heavy = false,
            languages = SpeechLanguages(setOf("zh"), separateChinese = true, primary = "zh"),
        )
        repeat(30) { speech.accept(FloatArray(FRAME) { Sound.ZH.value }) }
        speech.flush()
        assertTrue(speech.languagePurposes.isNotEmpty())
        assertEquals("zh", speech.events.filterIsInstance<SpeechEvent.CardFinished>().single().utterance?.lang)
    }

    /**
     * 区切りと、認識のワーカーの代わり。ジョブは待ち行列（[RecognitionQueue]）から取ってその場で処理し（言語の判定を先に）、
     * 書き起こしの結果は [latencyChunks] 個の音声（0.1秒ずつ）を渡した後に返す（0ならその場で返す）。言語の判定は速いので、
     * その場で返す（端末では識別のワーカーが別のスレッドで、0.3秒ほどで返す）。
     */
    private class Harness(
        recognizer: SpeechRecognizer,
        profile: SegmentationProfile,
        heavy: Boolean,
        languages: SpeechLanguages,
        private val latencyChunks: Int = 0,
        /** 言語の判定（[RecognitionJob.Language]・[RecognitionJob.Seam]）の結果を返すまでの音声の個数。 */
        private val languageLatencyChunks: Int = 0,
        /** 境界探し（[RecognitionJob.Seam]）の結果を返すまでの音声の個数。 */
        private val seamLatencyChunks: Int = languageLatencyChunks,
        /** ワーカーごとに1件ずつ処理する（端末と同じく、処理中のジョブの後ろに次のジョブが待ち行列で待つ）。 */
        private val oneJobPerLane: Boolean = false,
    ) {
        val events = mutableListOf<SpeechEvent>()

        /** セグメンターが書いた経過の行（端末ではログ、PC のリプレイでは結果のファイルに出る）。 */
        val traces = mutableListOf<String>()

        /** 頼まれた言語の判定の目的（`直近` など）。 */
        val languagePurposes = mutableListOf<String>()

        /** 頼まれたジョブ（頼んだ順）。 */
        val submitted = mutableListOf<RecognitionJob>()
        private val queue = RecognitionQueue()
        private val runner = RecognitionRunner(recognizer, recognizer as? LanguageIdentifier)
        private class Pending(val due: Int, val lane: RecognitionLane, val id: Long, val output: RecognitionOutput)

        private val waiting = ArrayDeque<Pending>()
        private var chunks = 0
        val segmenter = SpeechSegmenter(
            FakeVad((profile.phraseSilenceSeconds * 10).toInt()),
            recognizer is LanguageIdentifier,
            profile,
            heavy,
            emit = { events += it },
            submit = {
                if (it is RecognitionJob.Language) languagePurposes += it.purpose
                submitted += it
                queue.submit(it)
            },
            trace = {
                val message = it()
                traces += message
                if (System.getenv("SEGMENTER_TRACE") != null) println("[$chunks] $message")
            },
            // 時計は渡した音声の長さで進める（書き起こしの遅れを、端末と同じく途中結果の間隔に効かせる）
            clock = { chunks * 100_000_000L },
        ).apply { this.languages = languages }

        private fun run(all: Boolean) {
            while (true) {
                // 端末と同じく、閉じたカードの要らないジョブは捨て、空の結果を返す
                while (true) {
                    val stale = queue.dropStale(segmenter.openCardId)
                    if (stale.isEmpty()) break
                    stale.forEach { segmenter.onResult(it.id, RecognitionRunner.empty(it)) }
                }
                while (true) {
                    val job = listOf(RecognitionLane.IDENTIFY, RecognitionLane.TRANSCRIBE)
                        .filter { lane -> !oneJobPerLane || waiting.none { it.lane == lane } }
                        .firstNotNullOfOrNull { queue.take(it) } ?: break
                    val delay = when (job) {
                        is RecognitionJob.Transcribe -> latencyChunks
                        is RecognitionJob.Seam -> seamLatencyChunks
                        else -> languageLatencyChunks
                    }
                    waiting.addLast(Pending(chunks + delay, job.lane, job.id, kotlinx.coroutines.runBlocking { runner.run(job) }))
                }
                val due = waiting.filter { all || it.due <= chunks }.minByOrNull { it.due } ?: return
                waiting.remove(due)
                segmenter.onResult(due.id, due.output)
            }
        }

        fun accept(samples: FloatArray) {
            segmenter.accept(samples)
            chunks++
            run(all = false)
        }

        /** 頼んだジョブをすべて終わらせる。 */
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

    /** [parts] の音を0.1秒ずつ流し、出来事を集める（最後に、頼んだジョブを終わらせる）。 */
    private fun Harness.feed(vararg parts: Pair<Sound, Double>): List<SpeechEvent> = collect {
        parts.forEach { (sound, seconds) -> repeat((seconds * 10).toInt()) { accept(FloatArray(FRAME) { sound.value }) } }
        settle()
    }

    private fun finals(events: List<SpeechEvent>) = events.filterIsInstance<SpeechEvent.CardFinished>().mapNotNull { it.utterance }

    private fun partials(events: List<SpeechEvent>) = events.filterIsInstance<SpeechEvent.CardText>()

    /**
     * 採点の道具（`tools/score-conv.py`・`tools/score-speaker.py`）は、確定の行を `tools/conversation_log.py` の正規表現
     * `CARD_CLOSED` で読み、理由が `PERSON_REASONS` で始まる区切りを話した人によるものとして数える。行の形の正本は道具の側にあり、
     * ここで実際に書く行に当てて、食い違いを止める。
     */
    @Test
    fun 確定の行は採点の道具が読める形で書く() {
        val tool = listOf(File("../tools/conversation_log.py"), File("tools/conversation_log.py")).first { it.exists() }.readText()
        val closed = Regex(Regex("""(?m)^CARD_CLOSED = r"(.*)"$""").find(tool)!!.groupValues[1])
        val personReasons = Regex("""(?m)^PERSON_REASONS = \((.*)\)$""").find(tool)!!.groupValues[1]
            .split(',').map { it.trim().trim('"') }.filter { it.isNotEmpty() }

        val s = segmenter()
        val events = s.feed(Sound.JA to 1.5, Sound.SILENCE to 3.0) + // 話し終わり
            s.feed(Sound.JA to 4.0, Sound.KO to 4.0, Sound.SILENCE to 3.0) + // 話し中の話し手の交代（理由に括弧を含む）
            s.feed(Sound.JA to 1.5) + s.cut() + // 手で区切り
            s.feed(Sound.KO to 1.5) + s.flush() // 停止
        val cards = finals(events)
        val lines = s.traces.filter { it.startsWith("確定（") }.map { line -> closed.find(line) ?: throw AssertionError("採点の道具が読めない確定の行：$line") }
            .map { it.groupValues }.filter { it[5].isNotBlank() }

        assertEquals(cards.map { it.text }, lines.map { it[5] })
        assertEquals(cards.map { it.lang }, lines.map { it[4] })
        cards.zip(lines).forEach { (card, g) ->
            assertEquals(card.startMs / 1000.0, g[2].toDouble(), 0.01)
            assertEquals((card.startMs + card.durationMs) / 1000.0, g[3].toDouble(), 0.01)
        }
        val reasons = lines.map { it[1] }
        assertTrue(reasons.toString(), reasons.any { it.startsWith("話し手の交代（") && it.endsWith("）") })
        personReasons.forEach { person -> assertTrue("$person の区切りが出ない：$reasons", reasons.any { it.startsWith(person) }) }
    }

    @Test
    fun 話し終わりの無音で1枚のカードを確定する() {
        val events = segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 3.0)
        val cards = finals(events)
        assertEquals(1, cards.size)
        assertEquals("ja", cards[0].lang)
        assertTrue(cards[0].text.all { it == 'あ' })
        // カードを開き、話し終わりで閉じ、確定する（開く・閉じるはその場で、確定は書き起こしを待って）
        val id = cards[0].id
        assertEquals(SpeechEvent.CardOpened(id), events.first())
        assertTrue(events.indexOf(SpeechEvent.CardClosed(id)) < events.indexOfFirst { it is SpeechEvent.CardFinished })
        assertTrue(events.last() is SpeechEvent.CardFinished)
    }

    @Test
    fun 前のカードを書き起こしている間に話し始めてもその場でカードを開く() {
        // 書き起こしが2秒遅れる。話し終わってすぐ話し始めても、前のカードの確定を待たずに次のカードを開き、途中の文字を出す
        val events = segmenter(latency = 20).feed(Sound.JA to 1.5, Sound.SILENCE to 2.2, Sound.KO to 2.5, Sound.SILENCE to 3.0)
        val first = events.filterIsInstance<SpeechEvent.CardOpened>().map { it.card }
        assertEquals(2, first.size)
        val firstFinished = events.indexOfFirst { it is SpeechEvent.CardFinished }
        assertTrue(events.indexOf(SpeechEvent.CardOpened(first[1])) < firstFinished)
        // 確定はカードの順
        assertEquals(first, events.filterIsInstance<SpeechEvent.CardFinished>().map { it.card })
    }

    @Test
    fun 何も聞き取れなかったカードは発話の無い確定で閉じる() {
        val events = segmenter().feed(Sound.NOISE to 1.0, Sound.SILENCE to 3.0)
        val opened = events.filterIsInstance<SpeechEvent.CardOpened>()
        val finished = events.filterIsInstance<SpeechEvent.CardFinished>()
        assertEquals(opened.map { it.card }, finished.map { it.card })
        assertTrue(finished.all { it.utterance == null })
    }

    @Test
    fun 文末の後は途中認識を頼まず同じカードで話し直したら再開する() {
        val speech = segmenter(SpeechLanguages(specified = "ja"))
        speech.feed(Sound.JA to 1.5)
        assertTrue(speech.submitted.any { it.priority == RecognitionPriority.PARTIAL })

        speech.feed(Sound.SILENCE to profile.phraseSilenceSeconds.toDouble())
        val afterEnd = speech.submitted.size
        speech.feed(Sound.SILENCE to 1.0)
        assertTrue(speech.submitted.drop(afterEnd).none { it.priority == RecognitionPriority.PARTIAL })

        val beforeSpeech = speech.submitted.size
        speech.feed(Sound.JA to 1.5)
        assertTrue(speech.submitted.drop(beforeSpeech).any { it.priority == RecognitionPriority.PARTIAL })
        speech.feed(Sound.SILENCE to 3.0)
        assertEquals("あ".repeat(30), finals(speech.events).single().text)
    }

    @Test
    fun 言語判定が文末の後に届いても途中認識を頼まない() {
        val speech = Harness(
            HeavyRecognizer(), profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")),
            languageLatencyChunks = 20,
        )
        repeat(21) { speech.accept(FloatArray(FRAME) { Sound.JA.value }) }
        repeat(10) { speech.accept(FloatArray(FRAME)) }
        speech.settle()
        assertTrue(speech.submitted.any { it.priority == RecognitionPriority.PHRASE })
        assertTrue(speech.submitted.none { it.priority == RecognitionPriority.PARTIAL })
        assertEquals("ja", finals(speech.flush()).single().lang)
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
        // 区切りは言語の境界の近く：前のカードはほとんど日本語、後ろはほとんど韓国語
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
        assertTrue(events.takeLast(2).first() is SpeechEvent.CardFinished)
        assertEquals(SpeechEvent.Flushed, events.last())
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
        assertEquals(SpeechEvent.CardClosed(cut[0].id), events.first())
        // 聞くのは続けるので、続けて話した分は次のカードになり、話し終わりで確定する
        val rest = finals(s.feed(Sound.KO to 1.5, Sound.SILENCE to 3.0))
        assertEquals(listOf("ko"), rest.map { it.lang })
        // 話していないときに押しても何もしない
        assertEquals(emptyList<SpeechEvent>(), s.cut())
    }

    @Test
    fun 途中結果は同じ文字を続けて出さない() {
        val partials = partials(segmenter().feed(Sound.JA to 1.5, Sound.SILENCE to 1.5))
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
    fun 長い発話の区切りは書き起こしを頼んだ文の終わりより前に来ない() {
        // 文の終わりの直後の無音が、文の切れ目の前後で一番静かな所になる。そこで区切ると文がカードから外れ、
        // 区切った残りとして同じ音声を初めから書き起こし直す
        val s = segmenter()
        val cards = finals(s.feed(Sound.JA to 9.5, Sound.SILENCE to 0.7, Sound.JA to 10.0, Sound.SILENCE to 4.0))
        assertTrue(cards.size >= 2)
        val first = cards[0]
        val jobs = s.submitted.filter { it.card == first.id }
        // 文の言語の識別の書き起こしは、文の書き起こしに使い回す
        val phrase = jobs.filter { it.priority == RecognitionPriority.PHRASE || it.priority == RecognitionPriority.PHRASE_LANGUAGE }
            .maxOfOrNull { it.samples.size } ?: 0
        val rest = jobs.filter { it.priority == RecognitionPriority.FINAL }.sumOf { it.samples.size }
        assertTrue("文 $phrase", phrase > SAMPLE_RATE * 9)
        // 文と区切った残りを合わせても、カードの音声を2度は書き起こさない
        assertTrue("文 $phrase 残り $rest カード ${first.durationMs}ms", phrase + rest <= first.durationMs * SAMPLE_RATE / 1000 + FRAME)
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
        // 前のカードの途中の文字に、次の話し手の文字が混ざらない
        val firstCard = finals(events).first().id
        assertTrue(partials(events).filter { it.card == firstCard }.none { it.text.contains('가') })
        // 区切っても音声は欠けない
        assertEquals(15, finals(events)[1].text.length)
    }

    /**
     * 書き起こしの重いモデル（Qwen3-ASR）の代わり。書き起こさずにスコアを出せ（[LanguageIdentifier.scores]。決めた言語をスコア1にする）、
     * 書き起こした音声の長さ・識別の回数・言語を指定した書き起こしの回数を覚える。
     */
    private inner class HeavyRecognizer(
        /** 識別の結果を変える（受け取った音声の長さと、本来の判定から決める）。 */
        private val identifyAs: (samples: Int, lang: String?) -> String? = { _, lang -> lang },
    ) : SpeechRecognizer, LanguageIdentifier {
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

        override fun recognizeAs(samples: FloatArray, language: String?): Recognition {
            if (language == null) unspecified++
            return recognize(samples).copy(lang = language, source = LanguageSource.SPECIFIED)
        }

        override fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>? {
            identified++
            identifiedWindows += samples.size
            val lang = inner.recognize(samples).lang?.takeIf { candidates == null || it in candidates }
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
        // 言語の監視は識別だけで行い、カードの言語が決まった後の書き起こしは言語を指定する（識別を繰り返さない）
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
        val partials = partials(phraseSegmenter(HeavyRecognizer()).feed(Sound.KO to 2.5, Sound.SILENCE to 3.0))
        assertEquals(SpeechEvent.CardText(partials.first().card, "", "ko"), partials.first())
        assertTrue(partials.drop(1).any { it.text.isNotEmpty() })
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
    fun カードの言語を判定できなければ同じ区間を繰り返さず広い区間で判定し直す() {
        // 3秒より短い区間は判定できない（会話の外の言語と判定する）識別
        val asr = HeavyRecognizer { samples, lang -> lang.takeIf { samples >= SAMPLE_RATE * 3 } }
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko"), primary = "ja"))
        val events = s.feed(Sound.KO to 4.0, Sound.SILENCE to 3.0)
        val head = asr.identifiedWindows.filter { it >= SAMPLE_RATE * 2 }
        // 同じ長さの区間を何度も頼まない
        assertEquals(head.distinct().size, head.size)
        assertEquals("ko", finals(events).single().lang)
    }

    @Test
    fun 一番広い区間でも判定できなければ自分の言語として書き起こす() {
        val asr = HeavyRecognizer { _, _ -> null }
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko"), primary = "ja"))
        // 最初の文はカードの言語が決まる前に終わる（言語を指定せずに書き起こしを頼む）
        val events = s.feed(Sound.JA to 2.5, Sound.SILENCE to 1.0, Sound.JA to 5.0, Sound.SILENCE to 3.0)
        // 文字は話し終わる前に出る（言語が決まらないまま待たない）
        assertTrue(events.takeWhile { it !is SpeechEvent.CardFinished }.any { it is SpeechEvent.CardText && it.text.isNotEmpty() })
        assertEquals("ja", finals(events).single().lang)
        // 言語の手がかりが無くても、言語を決めずに書き起こさない（会話に無い言語の文を作り出すため）
        assertEquals(0, asr.unspecified)
    }

    @Test
    fun 話し中の区間で判定できなくても文の全体で話し手を決めて区切る() {
        // 2.5秒より短い区間は判定できない識別。韓国語の文の後、間を空けて日本語の文（3秒）
        val asr = HeavyRecognizer { samples, lang -> lang.takeIf { samples >= SAMPLE_RATE * 5 / 2 } }
        val cards = finals(phraseSegmenter(asr).feed(Sound.KO to 3.0, Sound.SILENCE to 1.0, Sound.JA to 3.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ko", "ja"), cards.map { it.lang })
        assertTrue(cards[1].text, cards[1].text.all { it == 'あ' })
    }

    @Test
    fun 書き起こしが重く途中結果の間隔が延びても話し中の交代にすぐ気づき境界で区切る() {
        // 書き起こしに1.5秒かかる（途中結果の間隔は3秒に延びる）。言語の識別はその場で返る
        val cards = finals(phraseSegmenter(HeavyRecognizer(), latency = 15).feed(Sound.JA to 4.0, Sound.KO to 5.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja", "ko"), cards.map { it.lang })
        // 区切りは言語の境界の近く：韓国語の頭が前のカードに残らない
        assertTrue(cards[0].text, cards[0].text.count { it == '가' } <= 3)
        assertTrue(cards[1].text, cards[1].text.count { it == '가' } >= 45)
    }

    @Test
    fun 言語の判定が遅いときは話し中の監視の間隔を延ばしてワーカーの時間を残す() {
        // 言語を自分で識別するモデル（SenseVoice）では、監視は書き起こしのワーカーで動く
        fun watches(languageLatency: Int): Int {
            val s = Harness(FakeRecognizer(::soundOf), profile, heavy = false, languages = SpeechLanguages(setOf("ja", "ko")),
                languageLatencyChunks = languageLatency)
            s.feed(Sound.JA to 10.0, Sound.SILENCE to 3.0)
            return s.languagePurposes.count { it == "話し中の区間" }
        }
        val fast = watches(0)
        val slow = watches(5)
        assertTrue("速い $fast 遅い $slow", slow * 2 <= fast)
    }

    @Test
    fun 文の判定が遅れて届いても文の頭で区切る() {
        // スコアは1.2秒遅れて届くので、判定より先に次の文が始まる
        val asr = HeavyRecognizer()
        val s = Harness(asr, profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")), languageLatencyChunks = 12)
        val cards = finals(s.feed(Sound.KO to 3.0, Sound.SILENCE to 1.0, Sound.JA to 1.8, Sound.SILENCE to 4.0))
        assertEquals(listOf("ko", "ja"), cards.map { it.lang })
    }

    @Test
    fun 文の頭で区切ったとき移した文を途中結果として書き起こさない() {
        // 文の判定が遅れて届き、文を次のカードへ移す。移した文は文として書き起こすので、途中結果で同じ音声を書き起こさない
        val s = Harness(HeavyRecognizer(), profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")), languageLatencyChunks = 12)
        s.feed(Sound.KO to 3.0, Sound.SILENCE to 1.0, Sound.JA to 1.8, Sound.SILENCE to 4.0)
        val next = s.submitted.filterIsInstance<RecognitionJob.Transcribe>().filter { it.card == 1L }
        assertEquals(RecognitionPriority.PHRASE, next.first().priority)
    }

    @Test
    fun 話し始めの区間が誤っても話し手が確かになる前は区切らず仮の言語を替える() {
        // 話し始めの区間（2〜2.5秒の音声）だけ、日本語を韓国語と判定する識別（PC で、日本語の頭の区間を韓国語 0.95 と判定した）。
        // 続く区間（1.5秒）と文の全体では正しく判定する
        val asr = HeavyRecognizer { samples, lang ->
            if (samples > SAMPLE_RATE * 3 / 2 && samples <= SAMPLE_RATE * 5 / 2 && lang == "ja") "ko" else lang
        }
        val events = phraseSegmenter(asr).feed(Sound.JA to 6.0, Sound.SILENCE to 3.0)
        val card = finals(events).single()
        assertEquals("ja", card.lang)
        assertTrue(card.text, card.text.all { it == 'あ' })
    }

    @Test
    fun 境界を探している間に話し終わり次の人が話し始めても話し終わりで分ける() {
        // 境界の結果が5秒遅れて届くので、その前に話し終わりの無音（3秒）が過ぎ、次の発話が始まる
        val s = Harness(HeavyRecognizer(), profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")), seamLatencyChunks = 50)
        val cards = finals(s.feed(Sound.JA to 5.0, Sound.KO to 2.5, Sound.SILENCE to 3.5, Sound.JA to 4.0, Sound.SILENCE to 6.0))
        assertEquals(listOf("ja", "ko", "ja"), cards.map { it.lang })
    }

    @Test
    fun 待ち行列で区間の判定を待っている間にカードを閉じても確定が出る() {
        // 識別のワーカーが1件ずつ処理し、判定に0.7秒かかる。文が終わった直後に話し、区間の判定が文の判定の後ろの待ち行列に
        // 残ったまま（その後ろに次の文の判定が並んだまま）手で区切る。捨てた区間に結果を返さないと、カードが確定しなかった
        val s = Harness(
            HeavyRecognizer(), profile, heavy = true, languages = SpeechLanguages(setOf("ja", "ko")),
            languageLatencyChunks = 7, oneJobPerLane = true,
        )
        fun Harness.play(vararg parts: Pair<Sound, Double>) =
            parts.forEach { (sound, seconds) -> repeat((seconds * 10).toInt()) { accept(FloatArray(FRAME) { sound.value }) } }

        val events = s.collect { play(Sound.JA to 4.0, Sound.SILENCE to 0.6, Sound.JA to 0.6) }
        val cut = events + s.cut()
        assertEquals(listOf("ja"), finals(cut).map { it.lang })
        assertTrue(cut.last() is SpeechEvent.CardFinished)

        val flushed = s.collect { play(Sound.KO to 4.0, Sound.SILENCE to 0.6, Sound.KO to 0.6) } + s.flush()
        assertEquals(listOf("ko"), finals(flushed).map { it.lang })
        assertTrue(flushed.last() is SpeechEvent.Flushed)
    }

    @Test
    fun 境界を探す区間で新しい言語が見つからなければ話し中に区切らない() {
        // 日本語だけの話で、話し中の区間（1.5秒）が続けて4回、韓国語と聞き違える識別。境界を探す短い区間は正しい
        var windows = 0
        val asr = HeavyRecognizer { samples, lang ->
            if (samples == SAMPLE_RATE * 3 / 2 && ++windows in 5..8) "ko" else lang
        }
        val cards = finals(phraseSegmenter(asr).feed(Sound.JA to 10.0, Sound.SILENCE to 3.0))
        assertEquals(listOf("ja"), cards.map { it.lang })
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
            RecognitionJob.Transcribe(id, card, p, FloatArray(0), 0, "", TranscriptLanguage.Unspecified)
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

    /**
     * 1.5秒の区間では韓国語を日本語と聞き違え（それらしい仮名を書く）、長い音声なら韓国語と判定する認識器
     * （作者の会話の録音の SenseVoice。話し中の区間の判定がカードの言語を日本語にしても、カードの書き起こしは韓国語だった）。
     */
    private class ShortWindowMishearing(private val inner: SpeechRecognizer) : SpeechRecognizer {
        override fun recognize(samples: FloatArray): Recognition {
            val r = inner.recognize(samples)
            return if (r.lang == "ko" && samples.size <= SAMPLE_RATE * 16 / 10) Recognition("こういうこと", "ja") else r
        }

        override fun close() = Unit
    }

    @Test
    fun 区間が言語を聞き違えてもカードの書き起こしの言語で確定と途中結果の言語を決める() {
        val s = Harness(ShortWindowMishearing(FakeRecognizer(::soundOf)), profile, heavy = false, languages = SpeechLanguages(setOf("ja", "ko")))
        val events = s.feed(Sound.KO to 6.0, Sound.SILENCE to 3.0)
        val card = finals(events).single()
        assertEquals("ko", card.lang)
        assertTrue(card.text.all { it == '가' })
        // 話し中のカードの言語（区間の判定）は日本語になっていた
        assertTrue(s.traces.toString(), s.traces.any { it.startsWith("カードの言語 ja") })
        // 文字のある途中結果は、書き起こしの言語が届いてからは韓国語として出す
        assertEquals("ko", partials(events).last { it.text.isNotEmpty() }.lang)
    }

    /** 結果の言語を指定の写し（[LanguageSource.SPECIFIED]）として返す認識器（Qwen3-ASR のように言語を指定して書き起こすもの）。 */
    private class SpecifiedLanguage(private val inner: SpeechRecognizer) : SpeechRecognizer {
        override fun recognize(samples: FloatArray) = inner.recognize(samples).copy(source = LanguageSource.SPECIFIED)
        override fun close() = Unit
    }

    @Test
    fun 指定した言語で書き起こした結果の言語はカードの言語の根拠にしない() {
        val recognizer = SpecifiedLanguage(ShortWindowMishearing(FakeRecognizer(::soundOf)))
        val s = Harness(recognizer, profile, heavy = false, languages = SpeechLanguages(setOf("ja", "ko")))
        // 区間は日本語と聞き違え、書き起こしは韓国語。書き起こしの言語が指定の写しなら、区間で決めた日本語のまま
        assertEquals("ja", finals(s.feed(Sound.KO to 6.0, Sound.SILENCE to 3.0)).single().lang)
    }

    @Test
    fun 確定を出すカードの音声を確定より先に渡す() {
        val retained = mutableListOf<Pair<Long, Int>>()
        val events = mutableListOf<SpeechEvent>()
        val queue = RecognitionQueue()
        val runner = RecognitionRunner(FakeRecognizer(::soundOf))
        val segmenter = SpeechSegmenter(
            FakeVad((profile.phraseSilenceSeconds * 10).toInt()), identifies = false, profile = profile,
            emit = { e ->
                if (e is SpeechEvent.CardFinished && e.utterance != null) assertTrue("確定より先に音声を渡す", retained.any { it.first == e.card })
                events += e
            },
            submit = queue::submit,
            retain = { id, audio -> retained += id to audio.size },
        ).apply { languages = SpeechLanguages(setOf("ja", "ko")) }
        repeat(15) { segmenter.accept(FloatArray(FRAME) { Sound.JA.value }) }
        repeat(30) {
            segmenter.accept(FloatArray(FRAME) { 0f })
            while (true) {
                val job = queue.take(RecognitionLane.TRANSCRIBE) ?: queue.take(RecognitionLane.IDENTIFY) ?: break
                segmenter.onResult(job.id, kotlinx.coroutines.runBlocking { runner.run(job) })
            }
        }
        val card = finals(events).single()
        // カードの音声（前に足した分を含む、確定の長さ）を渡す
        assertEquals(card.durationMs * SAMPLE_RATE / 1000.0, retained.single().second.toDouble(), SAMPLE_RATE / 1000.0)
    }
}
