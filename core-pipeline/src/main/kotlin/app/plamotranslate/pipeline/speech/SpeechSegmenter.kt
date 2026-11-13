package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE

/**
 * 区切りの状態遷移：音声を「文」と「カード」の2段に区切り、カードごとに認識する（docs/設計/会話翻訳.md「カードの区切り方」）。
 * VAD（[vad]）は「話しているか」と「文の終わり」の合図だけに使い、認識する音声は自分でためた [SpeechBuffer] から取る。
 * 途中結果と確定結果が同じ音声から作られ、区切っても音声が途切れない。
 *
 * カードは次のどれかで確定する。
 * - 文が終わってから無音が続き、合わせて [SegmentationProfile.endSilenceSeconds] になった（話し終わり）
 * - 次の文が前の文と違う言語で始まった（話し手の交代・間あり）
 * - 話している最中に、直近の言語が2回続けてカードの言語と違った（話し手の交代・間なし）
 * - [SegmentationProfile.splitAfterSeconds] 話し続けた（長い発話）
 *
 * 話している間は、ためた音声全体を0.5秒ごとに認識し直して途中結果を出す（疑似ストリーミング）。
 * 判断は部品に分ける：言語が話し手の交代の根拠になるかは [LanguageEvidence]、区切る位置は [CutPoint]、
 * 認識した文字の聞き違いの判定は [TranscriptCleanup]。
 *
 * 出来事を受け取って作業を返す純粋な状態遷移ではなく、VAD と認識（差し込み口）を同期で呼ぶ、状態を持つ区切りである
 * （1回の [accept] で認識を何度も呼ぶことがある）。認識にかかった時間は [clock]（ナノ秒）で測り、途中結果の間隔に使う。
 * [trace] は区切りの経過を書く口（話した言葉を含むので、デバッグ版だけが書く）。
 */
class SpeechSegmenter(
    private val vad: VoiceActivity,
    private val recognizer: SpeechRecognizer,
    private val profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    private val trace: (() -> String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) : SpeechEngine {
    private val prerollSamples = (profile.prerollSeconds * SAMPLE_RATE).toInt()
    private val splitAfterSamples = (profile.splitAfterSeconds * SAMPLE_RATE).toInt()

    /** 文の終わりで捨てる無音。0.2秒だけ残して語尾を切らないようにする。 */
    private val phraseTailDrop = ((profile.phraseSilenceSeconds - 0.2f) * SAMPLE_RATE).toInt()

    /** 文の終わりから、カードを確定させるまでに待つ無音。文の区切りと合わせて話し終わりの無音になる。 */
    private val cardEndGapSamples = ((profile.endSilenceSeconds - profile.phraseSilenceSeconds) * SAMPLE_RATE).toInt()

    /** ためる音声の上限（念のため）。区切りより長くしておく。 */
    private val buffer = SpeechBuffer(prerollSamples, splitAfterSamples + SAMPLE_RATE * 6)

    @Volatile override var separateChinese = false

    private fun evidence(r: Recognition?): String? = r?.let { LanguageEvidence.of(it, separateChinese) }

    /** 前の途中結果を出してから、話している音声がどれだけ増えたか。 */
    private var sinceLastPartial = 0

    /** 途中結果を出す間隔。認識にかかった時間の3倍より短くはしない（認識で音声の処理が遅れないように）。 */
    private var partialInterval = MIN_PARTIAL_INTERVAL

    /** 聞き始めから受け取った音声のサンプル数。発話の始まりの時刻に使う。 */
    private var received = 0L

    /** ためている音声の先頭が、聞き始めから何サンプル目か。 */
    private var bufferStart = 0L

    // 文（VADが区切る単位）の位置。どれも buffer の中の位置（サンプル）
    /** 今の文の始まり。 */
    private var phraseStart = 0

    /** 直前に終わった文の終わり。まだ無ければ -1。 */
    private var lastPhraseEnd = -1

    /** 直前に終わった文の言語（[LanguageEvidence] の組）。 */
    private var lastPhraseLang: String? = null

    /** 文が終わってから次の文が始まるまでの間（無音）にいるか。 */
    private var inGap = false
    private var gapSamples = 0

    /** 今の文の言語を、前の文と比べ終えたか。 */
    private var switchChecked = true

    /** このカードの言語（[LanguageEvidence] の組。最初の2秒で決める）。言語の交代を見張る基準。 */
    private var cardLang: String? = null

    /** 直近の音声の言語が、カードの言語と続けて何回違ったか。 */
    private var mismatches = 0

    /** 直前に途中結果として出した文字。同じ文字を続けて出さないために持つ。 */
    private var lastPartialText = ""

    /** 直前に認識し直した結果（後処理の後。出さなかったものも）。カードの最初の文の言語として使い、認識し直しを省く。 */
    private var lastPartial: Recognition? = null

    private var closed = false

    override fun accept(samples: FloatArray): List<SpeechEvent> {
        if (closed) return emptyList()
        val out = mutableListOf<SpeechEvent>()
        val signal = vad.accept(samples)
        val speaking = signal.speaking
        val phraseEnded = signal.phraseEnded
        received += samples.size

        if (!buffer.active) {
            if (!speaking && !phraseEnded) {
                buffer.idle(samples)
                return out
            }
            // 話し始め。VADの検出の遅れを補うため、直前の音声も頭に足す
            bufferStart = received - samples.size - buffer.prerollSize
            buffer.speaking(samples)
            out += SpeechEvent.Speaking(true)
            startCard()
        } else {
            buffer.speaking(samples)
        }

        if (phraseEnded) {
            // 文の終わり：区切りの無音を少しだけ残した位置を、文の終わりとして覚える
            val end = (buffer.size - phraseTailDrop).coerceAtLeast(phraseStart)
            // 中身のない文（雑音の聞き違い）は言語が無い。その場合は前の文の言語を引き継ぎ、
            // 雑音を挟んだ後の本当の話し手の交代も見分けられるようにする
            val phrase = if (phraseStart == 0 && lastPartial?.lang != null) lastPartial else recognizeRange(phraseStart, end, "文の終わり")
            lastPhraseLang = evidence(phrase) ?: lastPhraseLang
            lastPhraseEnd = end
            inGap = true
            gapSamples = 0
            trace { "文の終わり ${at(end)}、文の言語 $lastPhraseLang" }
        } else if (inGap) {
            if (speaking) {
                // 次の文が始まった。1秒たまったら、前の文と言語が同じか確かめる
                inGap = false
                phraseStart = lastPhraseEnd
                switchChecked = false
            } else {
                gapSamples += samples.size
                if (gapSamples >= cardEndGapSamples) {
                    // 話し終わり：最後の文の終わりまでを確定させる
                    finalOf(buffer.take(lastPhraseEnd), "話し終わり")?.let(out::add)
                    buffer.clear()
                    out += SpeechEvent.Speaking(false)
                    return out
                }
            }
        }

        if (!switchChecked && buffer.size - phraseStart >= SWITCH_CHECK_SAMPLES) {
            switchChecked = true
            val lang = evidence(recognizeRange(phraseStart, buffer.size, "次の文の頭"))
            if (lang != null && lastPhraseLang != null && lang != lastPhraseLang) {
                // 話し手が代わった：前の文までを確定させ、今の文から新しいカードにする
                cut(lastPhraseEnd, "言語の交代（間あり：$lastPhraseLang→$lang）")?.let(out::add)
                cardLang = lang
            }
        }

        if (buffer.size >= splitAfterSamples) {
            // 長く話し続けている：直近の静かなところで区切り、後ろの音声は次のカードの頭として残す
            val at = CutPoint.longUtterance(buffer, buffer.size - SPLIT_SEARCH_SAMPLES, buffer.size - MIN_TAIL_SAMPLES)
            cut(at, "長い発話")?.let(out::add)
        } else {
            sinceLastPartial += samples.size
            if (sinceLastPartial >= partialInterval && buffer.size >= MIN_PARTIAL_SAMPLES) {
                sinceLastPartial = 0
                watchLanguage()?.let(out::add)
                partial()?.let(out::add)
            }
        }
        return out
    }

    /**
     * 間を空けずに話し手が代わったときのため、話している最中も言語を見張る。
     * 直近1.5秒の言語が2回続けてカードの言語と違えば、話し手が代わったとみなし、言語の継ぎ目で区切る。
     * 1回だけで区切らないのは、外来語や固有名詞で言語の判定が一瞬揺れるため。
     */
    private fun watchLanguage(): SpeechEvent.Final? {
        if (cardLang == null) {
            if (buffer.size >= CARD_LANG_SAMPLES) cardLang = evidence(recognizeRange(0, CARD_LANG_SAMPLES, "カードの頭"))
            return null
        }
        if (buffer.size < CARD_LANG_SAMPLES + LANG_WINDOW_SAMPLES) return null
        val recent = evidence(recognizeRange(buffer.size - LANG_WINDOW_SAMPLES, buffer.size, "直近（カード $cardLang）"))
        if (recent == null || recent == cardLang) {
            mismatches = 0
            return null
        }
        if (++mismatches < 2) return null
        val at = switchPoint(buffer.size - SWITCH_SEARCH_SAMPLES, buffer.size - SWITCH_SEARCH_END_SAMPLES, recent)
        val final = cut(at, "言語の交代（話し中：$cardLang→$recent）")
        cardLang = recent
        return final
    }

    /**
     * 言語が代わった位置を探す。[from]〜[to] を0.25秒ずつずらしながら1秒ずつ言語を判定し、最初に [newLang] に
     * なったところを継ぎ目とみなして、その前後の区切る位置（[CutPoint.languageSeam]）を返す。
     * 継ぎ目探しは交代の根拠を決めるのではないので、認識器の言語をそのまま組にして比べる（[LanguageEvidence.group]）。
     * 静かさだけで探すと、話し手の継ぎ目に間が無いとき、新しい言語の文の中の小さな間を選び、
     * 新しい言語の最初の語が前のカードに入って消えた。
     */
    private fun switchPoint(from: Int, to: Int, newLang: String): Int {
        val start = from.coerceAtLeast(0)
        var s = start
        while (s <= to) {
            val probe = recognizeRange(s, s + LANG_PROBE_SAMPLES, "継ぎ目探し")
            if (LanguageEvidence.group(probe?.lang, separateChinese) == newLang) {
                return CutPoint.languageSeam(buffer, s - CutPoint.FRAME_SAMPLES * 2, s + CutPoint.FRAME_SAMPLES * 6)
            }
            s += LANG_PROBE_STEP_SAMPLES
        }
        return CutPoint.languageSeam(buffer, start, to)
    }

    /** 新しいカードを始めるときに、文の位置と途中結果をまっさらにする。 */
    private fun startCard() {
        sinceLastPartial = 0
        lastPartialText = ""
        lastPartial = null
        phraseStart = 0
        lastPhraseEnd = -1
        lastPhraseLang = null
        inGap = false
        gapSamples = 0
        switchChecked = true
        cardLang = null
        mismatches = 0
    }

    /** 話し続けたまま [at] で区切る。前を確定させ、後ろは次のカードの頭として残す。[reason] はログに出す区切りの理由。 */
    private fun cut(at: Int, reason: String): SpeechEvent.Final? {
        val final = finalOf(buffer.take(at), reason)
        mismatches = 0
        // 区切った後ろは別の話し手の可能性があるので、カードの言語は決め直す（呼び出し側が分かっていれば上書きする）
        cardLang = null
        phraseStart = (phraseStart - at).coerceAtLeast(0)
        lastPhraseEnd = if (lastPhraseEnd > at) lastPhraseEnd - at else -1
        if (lastPhraseEnd < 0) lastPhraseLang = null
        sinceLastPartial = 0
        lastPartialText = ""
        lastPartial = null
        return final
    }

    /** ためた音声の [from]〜[to] を認識する（言語の判定に使う）。短すぎて分からなければ null。[purpose] はログに出す目的。 */
    private fun recognizeRange(from: Int, to: Int, purpose: String): Recognition? {
        if (to - from < MIN_PARTIAL_SAMPLES) return null
        val r = recognize(buffer.slice(from, to))
        trace { "言語の判定（$purpose）${at(from)}〜${at(to)}: [${r.lang}] ${r.text}" }
        return r
    }

    /** ためた音声の中の位置を、聞き始めからの秒で書く（ログ用）。 */
    private fun at(pos: Int): String = "%.2f秒".format((bufferStart + pos).coerceAtLeast(0) / SAMPLE_RATE.toDouble())

    override fun flush(): List<SpeechEvent> {
        if (closed) return emptyList()
        vad.flush()
        if (!buffer.active) return emptyList()
        val out = mutableListOf<SpeechEvent>()
        finalOf(buffer.snapshot(), "停止")?.let(out::add)
        buffer.clear()
        out += SpeechEvent.Speaking(false)
        return out
    }

    override fun reset() {
        if (closed) return
        vad.reset()
        buffer.clear()
        partialInterval = MIN_PARTIAL_INTERVAL
        received = 0
        startCard()
    }

    /** ためた音声の先頭 [samples] を確定した発話として認識する。[reason] はログに出す区切りの理由。 */
    private fun finalOf(samples: FloatArray, reason: String): SpeechEvent.Final? {
        val startedAt = bufferStart
        bufferStart += samples.size
        val range = "%.2f〜%.2f秒".format(startedAt.coerceAtLeast(0) / SAMPLE_RATE.toDouble(), bufferStart / SAMPLE_RATE.toDouble())
        if (samples.size < MIN_PARTIAL_SAMPLES) {
            trace { "区切り（$reason）$range: 短いので捨てた" }
            return null
        }
        val start = clock()
        val r = recognize(samples)
        // 確定の文と、直前の途中の文を並べる（途中は合っていたのに確定で変わった例を見分けるため）
        trace { "区切り（$reason）$range: 確定 [${r.lang}] ${r.text} ／ 直前の途中 [${lastPartial?.lang}] $lastPartialText" }
        if (r.text.isEmpty()) return null
        return SpeechEvent.Final(
            Utterance(
                text = r.text,
                lang = r.lang,
                startMs = startedAt.coerceAtLeast(0) * 1000 / SAMPLE_RATE,
                durationMs = samples.size * 1000L / SAMPLE_RATE,
                recognizeMs = (clock() - start) / 1_000_000,
            ),
        )
    }

    /** 話し始めからここまでの音声を認識し直す。 */
    private fun partial(): SpeechEvent.Partial? {
        val start = clock()
        val r = recognize(buffer.snapshot())
        val costSamples = ((clock() - start) / 1_000_000 * SAMPLE_RATE / 1000).toInt()
        partialInterval = maxOf(MIN_PARTIAL_INTERVAL, costSamples * 3)
        lastPartial = r
        // 話し終わってから区切るまでの無音の間は同じ文字が続くので、変わったときだけ知らせる
        if (r.text.isEmpty() || r.text == lastPartialText) return null
        lastPartialText = r.text
        return SpeechEvent.Partial(r.text, r.lang)
    }

    private fun recognize(samples: FloatArray): Recognition = TranscriptCleanup.clean(recognizer.recognize(samples))

    /**
     * 解放した後に届いた呼び出しは何もしない（画面を閉じると、聞き取りを止めた後始末の [flush] が、
     * 解放より後に音声認識のスレッドへ回ってくることがある）。呼び出しはすべて同じ1本のスレッドから来る前提。
     */
    override fun close() {
        if (closed) return
        closed = true
        vad.close()
        recognizer.close()
    }

    companion object {
        /** 途中結果は0.5秒ごとに出す。 */
        private const val MIN_PARTIAL_INTERVAL = SAMPLE_RATE / 2

        /** 短すぎる音声は認識しても空になりやすいので、0.4秒たまってから認識する。 */
        private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** 次の文の言語を判定するのに使う長さ。短いと言語の判定が揺れる。 */
        private const val SWITCH_CHECK_SAMPLES = SAMPLE_RATE

        /** カードの言語を決める、カードの最初の音声の長さ。 */
        private const val CARD_LANG_SAMPLES = SAMPLE_RATE * 2

        /** 話している最中の言語の見張りに使う、直近の音声の長さ。 */
        private const val LANG_WINDOW_SAMPLES = SAMPLE_RATE * 3 / 2

        /** 言語が代わったと分かったときに、区切る位置を探す範囲（今から何サンプル前〜何サンプル前）。 */
        private const val SWITCH_SEARCH_SAMPLES = SAMPLE_RATE * 7 / 2
        private const val SWITCH_SEARCH_END_SAMPLES = SAMPLE_RATE

        /** 言語の継ぎ目を探すときに、1回に判定する長さと、ずらす幅。 */
        private const val LANG_PROBE_SAMPLES = SAMPLE_RATE
        private const val LANG_PROBE_STEP_SAMPLES = SAMPLE_RATE / 4

        /** 長い発話を区切る位置を探す範囲（直近4秒）と、区切った後ろに残す最小の長さ。 */
        private const val SPLIT_SEARCH_SAMPLES = SAMPLE_RATE * 4
        private const val MIN_TAIL_SAMPLES = SAMPLE_RATE * 3 / 10
    }
}
