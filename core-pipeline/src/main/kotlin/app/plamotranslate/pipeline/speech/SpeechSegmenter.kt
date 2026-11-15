package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE

/**
 * 区切りの状態遷移：音声を「文」と「カード」の2段に区切り、文ごとに話し手を決めて書き起こす（docs/設計/会話翻訳.md「カードの区切り方」）。
 * VAD（[vad]）は「話しているか」と「文の終わり」の合図だけに使い、認識する音声は自分でためた [SpeechBuffer] から取る。
 * 途中結果と確定結果が同じ音声から作られ、区切っても音声が途切れない。
 *
 * 誰が話しているか（話し手の言語）は、音声の点数（[RecognitionJob.Language]）から [SpeakerJudge] が決める。点数を頼む入口は2つ。
 * - 話し中の窓：話した音声0.5秒ごと（見張り自身にかかった時間で延びる）に直近1.5秒。カードの言語がまだ無ければ、話し始めからの音声
 *   （[SegmentationProfile.cardLanguageSeconds] から）。仮の言語を決め、話し手の交代に話し中に気づく。
 * - 文の全体：文が終わったとき。文の話し手を決め、その言語を指定して文を書き起こす。文の判定が、話し手の決まりの最後の機会になる。
 *
 * カードは次のどれかで確定する。
 * - 文が終わってから無音が続き、合わせて [SegmentationProfile.endSilenceSeconds] になった（話し終わり）
 * - 文の全体の話し手が、カードの確かな話し手と違った（話し手の交代。その文の頭で区切る）
 * - 話している最中に、直近の窓が続けてカードの確かな話し手と違い、継ぎ目の後ろの音声もそう決まった（話し手の交代・話し中）
 * - [SegmentationProfile.splitAfterSeconds] 話し続けた（長い発話）
 * - 利用者が区切った（[cut]）
 * カードの話し手が確かになるのは、文の全体で決まったときか、窓が [SegmentationProfile.liveSwitchChecks] 回続けて同じ言語と
 * 決まったとき。確かになる前に窓が別の言語に続けて決まったら、カードの全体の点数で、話し始めの窓の誤り（仮の言語を替える）か
 * 話し手の交代（継ぎ目で区切る）かを分ける（話し始めの短い窓は外れやすい）。
 *
 * 認識は待たない：書き起こしと点数は仕事（[RecognitionJob]）として [submit] に出し、結果を [onResult] で受け取ってから
 * 次を決める。音声の処理（VAD・区切り）は、重い書き起こしの間も止まらない（2026-11-15、作者の試用で、書き起こし中の表示が
 * 5〜7秒続いた。1本のスレッドで認識を待っていたので、1回の重い書き起こしの後ろに音声と区切りが並んでいた）。
 * 位置は聞き始めからのサンプル数で覚え、結果が届いた時点のためた音声の位置に直す。
 *
 * 出来事はカードの順に出す：前のカードの確定が届くまで、次のカードの出来事は [Card.held] に持っておく。
 * 確定に要る仕事は途中結果より先に処理される（[RecognitionQueue]）ので、待たせる時間はほとんど無い。
 *
 * 話している間は、まだ終わっていない文を、カードの言語で書き起こして途中結果を出す（書き起こした文につなぐ。疑似ストリーミング）。
 * カードの言語が決まるまでは書き起こさず、決まったら文字より先に知らせる（空の途中結果）。確定は、書き起こした文と、
 * 区切る位置までの残りをつなぐ。書き起こしの重いモデル（[heavy]、Qwen3-ASR）には、話し始めの前に足した音声のうち、
 * 声の始まり（[SpeechOnset]）より前を渡さない（書き起こしの時間は音声の長さに比例する）。
 *
 * 判断は部品に分ける：話し手は [SpeakerJudge]、区切る位置は [CutPoint]、認識した文字の聞き違いの判定は [TranscriptCleanup]、
 * 仕事の順番は [RecognitionQueue]。状態を持つので、すべての呼び出しは1本のスレッドから行う（[SpeechPipeline]）。
 * [trace] は区切りの経過を書く口（話した言葉を含むので、デバッグ版だけが書く）。
 */
class SpeechSegmenter(
    private val vad: VoiceActivity,
    /** 書き起こさずに点数を出せる認識器か（[SpeechRecognizer.identifies]）。出せれば点数は識別の担当に回す。 */
    private val identifies: Boolean,
    private val profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    /** 書き起こしの重いモデルか（[SpeechModel.heavy]）。声の始まりより前を渡さず、途中結果の間隔を詰める。 */
    private val heavy: Boolean = false,
    private val emit: (SpeechEvent) -> Unit,
    private val submit: (RecognitionJob) -> Unit,
    private val trace: (() -> String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
) {
    private val prerollSamples = (profile.prerollSeconds * SAMPLE_RATE).toInt()
    private val splitAfterSamples = (profile.splitAfterSeconds * SAMPLE_RATE).toInt()

    /** 文の終わりで捨てる無音。0.2秒だけ残して語尾を切らないようにする。 */
    private val phraseTailDrop = ((profile.phraseSilenceSeconds - 0.2f) * SAMPLE_RATE).toInt()

    /** 文の終わりから、カードを確定させるまでに待つ無音。文の区切りと合わせて話し終わりの無音になる。 */
    private val cardEndGapSamples = ((profile.endSilenceSeconds - profile.phraseSilenceSeconds) * SAMPLE_RATE).toInt()

    /** カードの言語を最初に判定する、話し始めからの音声の長さ。 */
    private val cardLangSamples = (profile.cardLanguageSeconds * SAMPLE_RATE).toInt().coerceAtLeast(MIN_PARTIAL_SAMPLES)

    /** ためる音声の上限（念のため）。前に足す音声と区切りより長くしておく。 */
    private val buffer = SpeechBuffer(prerollSamples, prerollSamples + splitAfterSamples + SAMPLE_RATE * 6)

    /** 点数を頼む担当。 */
    private val languageLane = if (identifies) RecognitionLane.IDENTIFY else RecognitionLane.TRANSCRIBE

    @Volatile var languages = SpeechLanguages.ANY

    /** 聞き始めから受け取った音声のサンプル数。 */
    private var received = 0L

    /** ためている音声の先頭が、聞き始めから何サンプル目か。 */
    private var bufferStart = 0L

    private fun abs(pos: Int): Long = bufferStart + pos
    private fun rel(at: Long): Int = (at - bufferStart).toInt()

    /** ためた音声のうち、話し始めの前に足した分の長さ。カードの言語の窓と途中結果は、ここから後ろで測る。 */
    private var speechStart = 0

    /** 直前に終わった文の終わり（ためた音声の中の位置）。まだ無ければ -1。 */
    private var lastPhraseEnd = -1

    /** 文が終わってから次の文が始まるまでの間（無音）にいるか。 */
    private var inGap = false
    private var gapSamples = 0

    /** 直前のカードの話し手の言語。決められない音声は、前の話し手として扱う。 */
    private var lastLang: String? = null

    /** 前の途中結果を頼んでから、話している音声がどれだけ増えたか。 */
    private var sinceLastPartial = 0

    /** 前に窓の点数を頼んでから、話している音声がどれだけ増えたか。 */
    private var sinceLastWatch = 0

    /**
     * 話し中の窓の間隔。見張りにかかった時間（待ち時間を含む）の何倍かより短くはしない。点数を識別の担当で出すモデルでは
     * 最短の間隔のまま、書き起こしの担当で出すモデル（SenseVoice）では、担当が混むと延びて途中結果の時間を残す。
     */
    private var watchInterval = WATCH_INTERVAL_SAMPLES

    /** 途中結果を頼む間隔。かかった時間の何倍かより短くはしない（CPU を途中結果で埋めない）。 */
    private var partialInterval = MIN_PARTIAL_INTERVAL

    /** 直前に届いた途中結果（後処理の後）と、それを書き起こした音声の始まり。区切る位置の候補に使う。 */
    private var lastPartial: Recognition? = null
    private var lastPartialStart = 0L

    /**
     * 文。[from]〜[end] は聞き始めからの位置。[lang] は話し手の言語（決めるまで null）、[recognition] は書き起こし（届くまで null）。
     * [audio] は文を作った時点の音声の写し。話し手を決めてから書き起こすまでに、ためた音声が区切られても書き起こせるようにする。
     */
    private class Phrase(val from: Long, val end: Long, var lang: String? = null, val audio: FloatArray? = null) {
        var recognition: Recognition? = null
    }

    /** 区切ると決めたカード。[parts] の書き起こしがすべて届いたら確定を出す。 */
    private class Closing(
        val start: Long,
        val end: Long,
        val reason: String,
        val startedAt: Long,
        val parts: List<Phrase>,
        val manual: Boolean,
        val speakingEnd: Boolean,
    )

    /** カード。[start] は聞き始めからの位置。 */
    private class Card(val id: Long, val start: Long) {
        val phrases = mutableListOf<Phrase>()

        /** 前のカードが終わるまで待たせている出来事。 */
        val held = mutableListOf<SpeechEvent>()

        /** このカードが終わった後に出す出来事（区切りを終えた・止め終えた）。 */
        val after = mutableListOf<SpeechEvent>()
        var closing: Closing? = null
        var done = false

        /** 話し手の言語（[SpeakerJudge] の組）。[sure] なら確か（文の全体で決まったか、窓が続けて同じ言語）、でなければ仮。 */
        var lang: String? = null
        var sure = false

        /** 窓の判定が、カードの言語と続けて何回同じだったか・違ったか（違った言語は [otherLang]）。 */
        var agreeing = 0
        var mismatches = 0
        var otherLang: String? = null

        /** まだ終わっていない文の途中結果と、それを書き起こした音声の始まり。 */
        var tail: Recognition? = null
        var tailFrom = 0L

        /** 直前に出した途中結果の文字。 */
        var lastText = ""
        var partialInFlight = false
        var watchPending = false
        var seamPending = false
    }

    /** まだ終わっていないカード（話した順）。先頭のカードの出来事だけを出す。 */
    private val cards = ArrayDeque<Card>()

    /** 音声をためているカード。話していなければ null。 */
    private var open: Card? = null
    private var nextCard = 0L

    private var nextJob = 0L
    private val handlers = HashMap<Long, (RecognitionOutput) -> Unit>()

    private var closed = false

    fun accept(samples: FloatArray) {
        if (closed) return
        val signal = vad.accept(samples)
        received += samples.size

        if (!buffer.active) {
            if (!signal.speaking && !signal.phraseEnded) {
                buffer.idle(samples)
                return
            }
            // 話し始め。VADの検出の遅れを補うため、直前の音声も頭に足す
            bufferStart = received - samples.size - buffer.prerollSize
            val preroll = buffer.prerollSize
            buffer.speaking(samples)
            startSpeech()
            speechStart = preroll
            send(openCard(), SpeechEvent.Speaking(true))
        } else {
            buffer.speaking(samples)
        }
        val card = open ?: return

        if (signal.phraseEnded) {
            // 文の終わり：区切りの無音を少しだけ残した位置を、文の終わりとして覚える
            val end = (buffer.size - phraseTailDrop).coerceAtLeast(stitchedEnd(card))
            phraseEnded(card, end)
            lastPhraseEnd = end
            inGap = true
            gapSamples = 0
            trace { "文の終わり ${at(abs(end))}" }
        } else if (inGap) {
            if (signal.speaking) {
                inGap = false
            } else {
                gapSamples += samples.size
                if (gapSamples >= cardEndGapSamples) {
                    // 話し終わり：書き起こした文をつなぐ（後ろは無音。重い書き起こしで確定を待たせない）。
                    // 短すぎて文として書き起こさなかった最後の文だけは、残りとして書き起こす
                    close(card, buffer.size, "話し終わり", restEnd = lastPhraseEnd, speakingEnd = true)
                    buffer.clear()
                    return
                }
            }
        }

        // 長さは話し始めから数える（前に足した音声の分だけ早く区切らない）
        if (buffer.size - speechStart >= splitAfterSamples) {
            // 長く話し続けている：文の切れ目（無ければ静かなところ）で区切り、後ろの音声は次のカードの頭として残す。話し手は同じ
            val at = CutPoint.longUtterance(
                buffer,
                breakFrom = buffer.size - SPLIT_BREAK_SEARCH_SAMPLES,
                quietFrom = buffer.size - SPLIT_SEARCH_SAMPLES,
                to = buffer.size - MIN_TAIL_SAMPLES,
                breaks = cutBreaks(card),
            )
            cutAt(at, "長い発話", manual = false, lang = card.lang, sure = card.sure)
        } else {
            sinceLastPartial += samples.size
            // 窓は話している音声だけで数える（無音の窓は、識別がどの言語とも言い切ることがある）
            if (!inGap) sinceLastWatch += samples.size
            if (sinceLastWatch >= watchInterval && !inGap) {
                sinceLastWatch = 0
                watchLanguage(card)
            }
            if (sinceLastPartial >= partialInterval && buffer.size - speechStart >= MIN_PARTIAL_SAMPLES) {
                sinceLastPartial = 0
                requestPartial(card)
            }
        }
    }

    /** 頼んだ仕事の結果。届いた時点で、まだ意味のある結果だけを使う（閉じたカードの途中結果などは捨てる）。 */
    fun onResult(id: Long, output: RecognitionOutput) {
        if (closed) return
        handlers.remove(id)?.invoke(output)
    }

    /** 残っている音声を確定させる。終えたら [SpeechEvent.Flushed] を出す。 */
    fun flush() {
        if (closed) return
        vad.flush()
        open?.let { card ->
            close(card, buffer.size, "停止", restEnd = buffer.size, speakingEnd = true)
            buffer.clear()
        }
        sendLast(SpeechEvent.Flushed)
    }

    /** 話している分を確定させ、続く音声は次のカードにする。終えたら [SpeechEvent.CutDone] を出す。 */
    fun cut() {
        if (closed) return
        if (!buffer.active || open == null) {
            sendLast(SpeechEvent.CutDone)
            return
        }
        cutAt(buffer.size, "手で区切り", manual = true)
    }

    fun reset() {
        if (closed) return
        vad.reset()
        buffer.clear()
        handlers.clear()
        cards.clear()
        open = null
        received = 0
        bufferStart = 0
        speechStart = 0
        lastLang = null
        partialInterval = MIN_PARTIAL_INTERVAL
        watchInterval = WATCH_INTERVAL_SAMPLES
        startSpeech()
    }

    /** 今カードを持っているか（ためた音声か、確定待ちのカードがある）。 */
    val idle: Boolean get() = cards.isEmpty()

    /** 今音声をためているカード（[RecognitionQueue.dropStale] に渡す）。 */
    val openCardId: Long? get() = open?.id

    fun close() {
        if (closed) return
        closed = true
        handlers.clear()
        vad.close()
    }

    // ---- 文とカード ----

    /** 話し始めたときに、文の位置をまっさらにする。 */
    private fun startSpeech() {
        lastPhraseEnd = -1
        inGap = false
        gapSamples = 0
        resetCardState()
    }

    private fun resetCardState() {
        sinceLastPartial = 0
        sinceLastWatch = 0
        lastPartial = null
        lastPartialStart = 0
    }

    private fun openCard(): Card = Card(nextCard++, bufferStart).also {
        cards.addLast(it)
        open = it
    }

    /** 決められない音声に使う言語：カードの言語、前の話し手、自分の言語の順。 */
    private fun fallbackLang(card: Card): String? = card.lang ?: lastLang ?: languages.primary

    /**
     * 文が終わった。[end] までのまだ書き起こしていない音声を1つの文にし、文の全体で話し手を決めてから書き起こす（[judgePhrase]）。
     * 短すぎる文は次の文と一緒にする。[lang] があれば、話し手は決まっている（区切った前のカードで決めた文）。
     */
    private fun phraseEnded(card: Card, end: Int, lang: String? = null) {
        val from = stitchedEnd(card)
        if (end - from < MIN_PARTIAL_SAMPLES) return
        val phrase = Phrase(abs(from), abs(end), lang, capture(from, end))
        card.phrases += phrase
        if (lang != null) {
            transcribePhrase(card, phrase, null)
            return
        }
        scores(card, phrase.from, phrase.end, RecognitionPriority.PHRASE_LANGUAGE, PURPOSE_PHRASE) { scores, recognition ->
            if (phrase !in card.phrases) return@scores
            judgePhrase(card, phrase, SpeakerJudge.decide(scores, languages, SpeakerJudge.PHRASE), recognition)
        }
    }

    /**
     * 文の話し手が [judged] と決まった（決められなければ null）。カードの確かな話し手と違えば、文の頭で区切り、文は次のカードで
     * 書き起こす。同じか、カードの話し手がまだ確かでなければ、文の話し手をカードの話し手にして書き起こす。
     * 閉じると決めたカードの文は、区切らずに決めた言語で書き起こす（確定を待たせない）。
     */
    private fun judgePhrase(card: Card, phrase: Phrase, judged: String?, recognition: Recognition?) {
        val current = card.lang
        if (card === open && judged != null && current != null && card.sure && judged != current) {
            val at = rel(phrase.from)
            if (at > 0 && at <= buffer.size) {
                // 決めた話し手を文に持たせて次のカードへ渡す（同じ音声をもう一度判定しない）
                phrase.lang = judged
                cutAt(at, "話し手の交代（文：$current→$judged）", manual = false, lang = judged, sure = true)
                return
            }
        }
        if (card === open && judged != null) {
            if (judged != current) setCardLang(card, judged, sure = true) else card.sure = true
        }
        phrase.lang = judged ?: fallbackLang(card)
        transcribePhrase(card, phrase, recognition)
    }

    /**
     * 文を話し手の言語で書き起こす。点数を出すのに書き起こした認識器（SenseVoice）は、その書き起こし（[recognition]）を使う
     * （言語を指定できないので、書き起こし直しても同じ）。
     */
    private fun transcribePhrase(card: Card, phrase: Phrase, recognition: Recognition?) {
        fun done(r: Recognition) {
            phrase.recognition = r
            if (card === open) showStitched(card)
            finishIfReady(card)
        }
        if (recognition != null) {
            trace { "書き起こし（文・点数のもの）${at(phrase.from)}〜${at(phrase.end)}: [${recognition.lang}] ${recognition.text}" }
            done(recognition)
            return
        }
        transcribe(card, phrase, RecognitionPriority.PHRASE, "文", phrase.lang) { r -> done(r) }
    }

    /** 書き起こした（頼んだ）文の終わり（ためた音声の中の位置）。ここから後ろが、まだ書き起こしていない音声。 */
    private fun stitchedEnd(card: Card): Int = card.phrases.lastOrNull()?.let { rel(it.end) } ?: rel(card.start).coerceAtLeast(0)

    /**
     * 話している最中の窓の点数で、カードの言語を決め、話し手の交代を見張る。カードの言語がまだ無ければ、話し始めからの音声
     * （[SegmentationProfile.cardLanguageSeconds] から）で決める。決まったら直近1.5秒を見て、決まった言語がカードの言語と
     * [SegmentationProfile.liveSwitchChecks] 回続けて違えば、カードの話し手が確かなら継ぎ目を探して区切り（[findSeam]）、
     * まだ確かでなければカードの仮の言語を替える。決められない窓は、交代の根拠にも反証にもしない。
     * 話し始めから長く話してもカードの言語が決まらなければ、前の話し手の言語を仮に使う（決まらないと途中結果を出せない）。
     */
    private fun watchLanguage(card: Card) {
        if (buffer.size < speechStart + cardLangSamples) return
        if (card.watchPending || card.seamPending) return
        if (card.lang == null && buffer.size - speechStart >= CARD_LANG_MAX_SAMPLES) {
            fallbackLang(card)?.let { setCardLang(card, it, sure = false) }
        }
        val from = if (card.lang == null) speechStart else (buffer.size - LANG_WINDOW_SAMPLES).coerceAtLeast(speechStart)
        card.watchPending = true
        val submittedAt = clock()
        scores(card, abs(from), abs(buffer.size), RecognitionPriority.LANGUAGE, PURPOSE_WATCH) { scores, _ ->
            card.watchPending = false
            val costSamples = ((clock() - submittedAt) / 1_000_000 * SAMPLE_RATE / 1000).toInt()
            watchInterval = maxOf(WATCH_INTERVAL_SAMPLES, costSamples * WATCH_COST_RATIO)
            if (card !== open) return@scores
            val lang = SpeakerJudge.decide(scores, languages, SpeakerJudge.WINDOW) ?: return@scores
            val current = card.lang
            when {
                current == null -> {
                    setCardLang(card, lang, sure = false)
                    card.agreeing = 1
                }
                lang == current -> {
                    card.mismatches = 0
                    if (++card.agreeing >= profile.liveSwitchChecks) card.sure = true
                }
                else -> {
                    if (card.otherLang != lang) card.mismatches = 0
                    card.otherLang = lang
                    if (++card.mismatches < profile.liveSwitchChecks) return@scores
                    if (card.sure) findSeam(card, lang) else recheckCard(card, lang)
                }
            }
        }
    }

    /**
     * まだ確かでないカードの言語と違う窓が続いた。話し始めの窓の誤りか、話し手の交代かを、カードの全体の点数で分ける。
     * 全体も [newLang] なら、最初の窓が誤っていたので仮の言語を替える（PC で、作者の日本語の頭の1.5秒を韓国語 0.95〜0.99 と
     * 判定した）。そうでなければ、話し手が代わったとみなして継ぎ目を探す。
     */
    private fun recheckCard(card: Card, newLang: String) {
        card.seamPending = true
        val previous = card.lang
        scores(card, abs(speechStart), abs(buffer.size), RecognitionPriority.LANGUAGE, PURPOSE_CARD) { scores, _ ->
            card.seamPending = false
            if (card !== open || card.lang != previous) return@scores
            if (SpeakerJudge.decide(scores, languages, SpeakerJudge.PHRASE) == newLang) {
                trace { "カードの仮の言語を替える $previous→$newLang" }
                val agreeing = card.mismatches
                setCardLang(card, newLang, sure = true)
                card.agreeing = agreeing
            } else {
                findSeam(card, newLang)
            }
        }
    }

    /** カードの言語を決める。まだ文字の出ていないカードなら、空の途中結果で言語を先に知らせ、すぐ書き起こす。 */
    private fun setCardLang(card: Card, lang: String, sure: Boolean) {
        val changed = card.lang != lang
        card.lang = lang
        card.sure = sure
        if (!changed) return
        card.agreeing = 0
        card.mismatches = 0
        card.otherLang = null
        trace { "カードの言語 $lang（${if (sure) "確か" else "仮"}）" }
        if (card !== open) return
        // 仮の言語で書き起こした途中結果は古いので、決めた言語で書き起こし直す
        card.tail = null
        if (card.lastText.isEmpty()) send(card, SpeechEvent.Partial("", lang))
        sinceLastPartial = 0
        requestPartial(card)
    }

    /**
     * 言語が代わった位置を探して区切る。3.5秒前〜1秒前を0.25秒ずつずらした1秒の窓のうち、最初に [newLang] になったところを
     * 継ぎ目とみなし、継ぎ目の後ろの音声の全体も [newLang] と決まれば、その前後の区切る位置（[CutPoint.languageSeam]）で区切る。
     * 窓は二分探索で選ぶ（[RecognitionJob.Seam]）。後ろの音声が [newLang] と決まらなければ区切らない（短い窓の聞き違い）。
     * 静かさだけで探すと、話し手の継ぎ目に間が無いとき、新しい言語の文の中の小さな間を選び、新しい言語の最初の語が前のカードに入って消えた。
     */
    private fun findSeam(card: Card, newLang: String) {
        card.seamPending = true
        val from = (buffer.size - SWITCH_SEARCH_SAMPLES).coerceAtLeast(0)
        val to = buffer.size - SWITCH_SEARCH_END_SAMPLES
        val fromAbs = abs(from)
        val previous = card.lang
        job(
            { id ->
                RecognitionJob.Seam(
                    id, card.id, languageLane, buffer.slice(from, to + LANG_PROBE_SAMPLES), abs(to + LANG_PROBE_SAMPLES),
                    PURPOSE_SEAM, languages, newLang, LANG_PROBE_SAMPLES, LANG_PROBE_STEP_SAMPLES,
                )
            },
        ) { out ->
            card.seamPending = false
            if (card !== open) return@job
            val seam = (out as? RecognitionOutput.SeamFound)?.at
            trace { "継ぎ目 ${seam?.let { at(fromAbs + it) } ?: "なし"}（$previous→$newLang）" }
            if (seam == null) {
                card.mismatches = 0
                return@job
            }
            val s = rel(fromAbs) + seam
            val at = CutPoint.languageSeam(buffer, s - CutPoint.FRAME_SAMPLES * 2, s + CutPoint.FRAME_SAMPLES * 6)
            cutAt(at, "話し手の交代（話し中：$previous→$newLang）", manual = false, lang = newLang, sure = false)
        }
    }

    /**
     * 話し続けたまま [at] で区切る。前を確定させ、後ろは次のカードの頭として残す。[lang] は後ろの話し手（分かっていれば）。
     * 区切る位置より後ろで終わった文は、次のカードの文として書き起こし直す（前のカードからは外すので、移さないと言葉が消える）。
     * 区切る位置から始まる文は、決めた話し手をそのまま使う。
     */
    private fun cutAt(at: Int, reason: String, manual: Boolean, lang: String? = null, sure: Boolean = false) {
        val card = open ?: return
        val cutAbs = abs(at)
        val moved = card.phrases.filter { it.end > cutAbs }.map { p -> p.end to p.lang?.takeIf { p.from >= cutAbs } }
        close(card, at, reason, restEnd = at, manual = manual)
        lastLang = card.lang ?: lastLang
        buffer.take(at)
        bufferStart += at
        speechStart = (speechStart - at).coerceAtLeast(0)
        lastPhraseEnd = if (lastPhraseEnd > at) lastPhraseEnd - at else -1
        resetCardState()
        val next = openCard()
        if (lang != null) setCardLang(next, lang, sure)
        moved.forEach { (end, known) -> phraseEnded(next, rel(end), known) }
    }

    /**
     * カードを [at]（ためた音声の中の位置）で区切ると決める。書き起こした文をつなぎ、[restEnd] までのまだ書き起こしていない
     * 音声（区切る位置が文の途中なら、その文の頭から）を、話し手を決めてから書き起こす。書き起こしがすべて届いたら確定を出す（[finishIfReady]）。
     */
    private fun close(card: Card, at: Int, reason: String, restEnd: Int, manual: Boolean = false, speakingEnd: Boolean = false) {
        val start = card.start
        val end = abs(at)
        card.phrases.removeAll { it.end > end }
        val parts = card.phrases.toMutableList()
        val from = parts.lastOrNull()?.end ?: start
        val restTo = abs(restEnd).coerceAtMost(end)
        if (restTo - from >= MIN_REST_SAMPLES) {
            val rest = Phrase(from, restTo, audio = capture(rel(from), rel(restTo)))
            parts += rest
            scores(card, rest.from, rest.end, RecognitionPriority.PHRASE_LANGUAGE, PURPOSE_REST) { scores, recognition ->
                rest.lang = SpeakerJudge.decide(scores, languages, SpeakerJudge.PHRASE) ?: fallbackLang(card)
                if (recognition != null) {
                    rest.recognition = recognition
                    finishIfReady(card)
                    return@scores
                }
                transcribe(card, rest, RecognitionPriority.FINAL, "確定の残り", rest.lang) { r ->
                    rest.recognition = r
                    finishIfReady(card)
                }
            }
        }
        card.closing = Closing(start, end, reason, clock(), parts, manual, speakingEnd)
        if (open === card) open = null
        trace { "区切ると決めた（$reason）${at(start)}〜${at(end)}" }
        finishIfReady(card)
    }

    /** 区切ると決めたカードの書き起こしがすべて届いていれば、確定を出す。言語はカードの話し手（決まっていなければ書き起こしの言語）。 */
    private fun finishIfReady(card: Card) {
        val closing = card.closing ?: return
        if (card.done || closing.parts.any { it.recognition == null }) return
        val r = TranscriptJoin.stitch(closing.parts.mapNotNull { it.recognition })
        val lang = card.lang ?: r.lang
        val waitedMs = (clock() - closing.startedAt) / 1_000_000
        trace { "確定（${closing.reason}）${at(closing.start)}〜${at(closing.end)}: [$lang] ${r.text} ／ 直前の途中 ${card.lastText}（区切ってから${waitedMs}ms）" }
        if (r.text.isNotEmpty()) {
            send(
                card,
                SpeechEvent.Final(
                    Utterance(
                        text = r.text,
                        lang = lang,
                        startMs = closing.start.coerceAtLeast(0) * 1000 / SAMPLE_RATE,
                        durationMs = (closing.end - closing.start) * 1000 / SAMPLE_RATE,
                        recognizeMs = waitedMs,
                    ),
                ),
            )
        }
        if (closing.manual) send(card, SpeechEvent.CutDone)
        if (closing.speakingEnd) send(card, SpeechEvent.Speaking(false))
        card.done = true
        // 話し終わりで閉じたカードの話し手は、次に話し始めたカードの「前の話し手」
        if (open == null) lastLang = card.lang ?: lastLang
        pump()
    }

    // ---- 途中結果 ----

    /**
     * 途中結果を頼む（同じカードで1件ずつ）。カードの言語が決まってから、まだ終わっていない文だけを書き起こし、
     * 書き起こした文につなぐ（言語が決まる前に書くと、会話に無い言語で書き起こされる）。
     */
    private fun requestPartial(card: Card) {
        if (card.partialInFlight || card !== open) return
        val lang = card.lang ?: return
        val from = stitchedEnd(card)
        if (buffer.size - from < MIN_PARTIAL_SAMPLES) return
        val fromAbs = abs(from)
        val phrase = Phrase(fromAbs, abs(buffer.size), lang)
        val submittedAt = clock()
        card.partialInFlight = true
        transcribe(card, phrase, RecognitionPriority.PARTIAL, "途中", lang) { r ->
            card.partialInFlight = false
            // 途中結果の間隔は、かかった時間（待ち時間を含む）の何倍か。音声の処理は止まらないので、CPU の取り分だけを決める
            val costSamples = ((clock() - submittedAt) / 1_000_000 * SAMPLE_RATE / 1000).toInt()
            partialInterval = maxOf(MIN_PARTIAL_INTERVAL, costSamples * if (heavy) HEAVY_PARTIAL_COST_RATIO else PARTIAL_COST_RATIO)
            if (card !== open || card.lang != lang) return@transcribe
            lastPartial = r
            lastPartialStart = fromAbs
            card.tail = r
            card.tailFrom = fromAbs
            showStitched(card)
        }
    }

    /** 書き起こした文（届いた分だけ、頭から続くもの）と、まだ終わっていない文の途中結果をつないで出す。 */
    private fun showStitched(card: Card) {
        val parts = mutableListOf<Recognition>()
        var complete = true
        for (p in card.phrases) {
            val r = p.recognition
            if (r == null) {
                complete = false
                break
            }
            parts += r
        }
        // 途中結果は、最後の文の後ろを書き起こしたものだけをつなぐ（文が増えたら古い）
        val tail = card.tail
        if (complete && tail != null && card.tailFrom == (card.phrases.lastOrNull()?.end ?: card.start)) parts += tail
        if (parts.isEmpty()) return
        showPartial(card, TranscriptJoin.stitch(parts))
    }

    /** 途中結果を知らせる。同じ文字を続けて出さない（話し終わってから区切るまでの無音の間は同じ文字が続く）。言語はカードの話し手。 */
    private fun showPartial(card: Card, r: Recognition) {
        if (r.text.isEmpty() || r.text == card.lastText) return
        card.lastText = r.text
        send(card, SpeechEvent.Partial(r.text, card.lang ?: r.lang))
    }

    /**
     * 長い発話を区切る位置の候補（文の切れ目）。書き起こした文の終わり（VAD の文の区切り）と、
     * 直前の途中結果の切れ目を、ためた音声の中の位置に直す。
     */
    private fun cutBreaks(card: Card): List<SpeechBreak> {
        val offset = rel(lastPartialStart)
        val partialBreaks = lastPartial?.breaks.orEmpty().map { it.copy(at = it.at + offset) }
        return card.phrases.map { SpeechBreak(rel(it.end), sentence = true) } + partialBreaks
    }

    // ---- 仕事と出来事 ----

    private fun job(make: (Long) -> RecognitionJob, handler: (RecognitionOutput) -> Unit) {
        val id = nextJob++
        handlers[id] = handler
        submit(make(id))
    }

    /**
     * [phrase] の音声を [language] として書き起こしてもらう（識別を省く）。null なら識別してもらい、識別できなければ
     * 前の話し手の言語として書き起こす。書き起こしの重いモデルは、話し始めの前に足した音声のうち、声の始まりより前を渡さない。
     */
    private fun transcribe(card: Card, phrase: Phrase, priority: RecognitionPriority, purpose: String, language: String?, then: (Recognition) -> Unit) {
        val samples = phrase.audio ?: capture(rel(phrase.from), rel(phrase.end))
        val fallback = fallbackLang(card)
        job(
            { id -> RecognitionJob.Transcribe(id, card.id, priority, samples, phrase.end, purpose, languages, language, fallback) },
        ) { out ->
            val r = TranscriptCleanup.clean((out as? RecognitionOutput.Transcribed)?.recognition ?: Recognition("", null))
            trace { "書き起こし（$purpose）${at(phrase.from)}〜${at(phrase.end)} 言語の指定 $language: [${r.lang}] ${r.text}" }
            then(r)
        }
    }

    /** ためた音声の [from]〜[to] の写し（書き起こしに渡す）。書き起こしの重いモデルは、話し始めの前に足した音声のうち声の始まりより前を除く。 */
    private fun capture(from: Int, to: Int): FloatArray {
        val end = to.coerceIn(0, buffer.size)
        var start = from.coerceIn(0, end)
        if (heavy && start == 0 && speechStart > 0) start = SpeechOnset.start(buffer.slice(0, speechStart), speechStart).coerceAtMost(end)
        return buffer.slice(start, end)
    }

    /**
     * 聞き始めからの位置 [from]〜[to] の音声の、会話の言語ごとの点数を出してもらう。短すぎればすぐ null を返す。
     * 書き起こして点数を出した認識器は、その書き起こし（後処理の後）も返す。
     */
    private fun scores(
        card: Card,
        from: Long,
        to: Long,
        priority: RecognitionPriority,
        purpose: String,
        then: (Map<String, Float>?, Recognition?) -> Unit,
    ) {
        val start = rel(from).coerceAtLeast(0)
        val end = rel(to).coerceAtMost(buffer.size)
        if (end - start < MIN_PARTIAL_SAMPLES) {
            then(null, null)
            return
        }
        job({ id -> RecognitionJob.Language(id, card.id, languageLane, priority, buffer.slice(start, end), to, purpose, languages) }) { out ->
            val scored = out as? RecognitionOutput.Scored
            val recognition = scored?.recognition?.let(TranscriptCleanup::clean)
            trace { "点数（$purpose）${at(from)}〜${at(to)}: ${scored?.scores?.entries?.sortedByDescending { it.value }?.take(3)?.joinToString { "%s=%.2f".format(it.key, it.value) }}" }
            then(scored?.scores, recognition)
        }
    }

    /** [card] の出来事を出す。前のカードがまだ終わっていなければ、終わるまで持っておく。 */
    private fun send(card: Card, event: SpeechEvent) {
        if (cards.firstOrNull() === card) emit(event) else card.held += event
    }

    /** 今あるカードがすべて終わった後に出す出来事。カードが無ければすぐ出す。 */
    private fun sendLast(event: SpeechEvent) {
        val last = cards.lastOrNull()
        if (last == null) emit(event) else last.after += event
    }

    /** 終わったカードを先頭から外し、次のカードの持っていた出来事を出す。 */
    private fun pump() {
        while (true) {
            val head = cards.firstOrNull() ?: return
            if (!head.done) return
            cards.removeFirst()
            head.after.forEach(emit)
            val next = cards.firstOrNull() ?: return
            next.held.forEach(emit)
            next.held.clear()
        }
    }

    /** 聞き始めからの位置を秒で書く（ログ用）。 */
    private fun at(pos: Long): String = "%.2f秒".format(pos.coerceAtLeast(0) / SAMPLE_RATE.toDouble())

    companion object {
        /** 途中結果は0.5秒ごとより頻繁には頼まない。 */
        private const val MIN_PARTIAL_INTERVAL = SAMPLE_RATE / 2

        /** 話している最中に窓の点数を頼む最短の間隔（話した音声の長さ）。途中結果の間隔とは別に数える。 */
        private const val WATCH_INTERVAL_SAMPLES = SAMPLE_RATE / 2

        /** 見張りの間隔を、見張りにかかった時間の何倍より短くしないか（見張りが担当の時間の3分の1より多くを取らない）。 */
        private const val WATCH_COST_RATIO = 3

        /** 途中結果の間隔を、かかった時間の何倍にするか（書き起こしの重いモデルは2倍、ほかは3倍）。 */
        private const val HEAVY_PARTIAL_COST_RATIO = 2
        private const val PARTIAL_COST_RATIO = 3

        /** 短すぎる音声は認識しても空になりやすいので、0.4秒たまってから認識する。 */
        private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** 区切るときに、文として書き起こしていない残りを書き起こす最短の長さ（短い「はい」を落とさない）。 */
        private const val MIN_REST_SAMPLES = SAMPLE_RATE / 4

        /** 話し始めからこの長さ話してもカードの言語が決まらなければ、前の話し手の言語を仮に使う。 */
        private const val CARD_LANG_MAX_SAMPLES = SAMPLE_RATE * 5

        /** 話している最中の窓に使う、直近の音声の長さ。 */
        private const val LANG_WINDOW_SAMPLES = SAMPLE_RATE * 3 / 2

        /** 言語が代わったと分かったときに、区切る位置を探す範囲（今から何サンプル前〜何サンプル前）。 */
        private const val SWITCH_SEARCH_SAMPLES = SAMPLE_RATE * 7 / 2
        private const val SWITCH_SEARCH_END_SAMPLES = SAMPLE_RATE

        /** 言語の継ぎ目を探すときに、1回に判定する長さと、ずらす幅。 */
        private const val LANG_PROBE_SAMPLES = SAMPLE_RATE
        private const val LANG_PROBE_STEP_SAMPLES = SAMPLE_RATE / 4

        /**
         * 長い発話を区切る位置を探す範囲。文の切れ目は直近6秒（カードが読める長さ（約12秒）より短くなりすぎない範囲で、
         * 文の終わりを見つけやすくする）、切れ目が無いときの静かなところは直近4秒。区切った後ろに残す最小の長さ。
         */
        private const val SPLIT_BREAK_SEARCH_SAMPLES = SAMPLE_RATE * 6
        private const val SPLIT_SEARCH_SAMPLES = SAMPLE_RATE * 4
        private const val MIN_TAIL_SAMPLES = SAMPLE_RATE * 3 / 10

        /** 点数を頼む目的（ログに出す）。窓の点数はカードごとに1件ずつ頼む。 */
        private const val PURPOSE_WATCH = "話し中の窓"
        private const val PURPOSE_CARD = "カードの全体"
        private const val PURPOSE_PHRASE = "文の全体"
        private const val PURPOSE_REST = "区切った残り"
        private const val PURPOSE_SEAM = "継ぎ目探し"
    }
}
