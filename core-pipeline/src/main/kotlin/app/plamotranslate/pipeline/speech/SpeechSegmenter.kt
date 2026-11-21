package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.CardTranscript.Phrase
import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE

/**
 * セグメンターの状態遷移：音声を「文」と「カード」の2段に区切り、文ごとに話し手を決めて書き起こす（docs/設計/会話翻訳.md「カードの区切り方」）。
 * VAD（[vad]）は「話しているか」と「文の終わり」の判定だけに使い、認識する音声は自分でためた [SpeechBuffer] から取る。
 * 途中結果と確定結果が同じ音声から作られ、区切っても音声が途切れない。
 *
 * 誰が話しているか（話し手の言語）は、音声のスコア（[RecognitionJob.Language]）から [SpeakerJudge] が決める。スコアを頼む入口は2つ。
 * - 話し中の区間：話した音声0.5秒ごと（監視自身にかかった時間で延びる）に直近1.5秒。カードの言語がまだ無ければ、話し始めからの音声
 *   （[SegmentationProfile.cardLanguageSeconds] から）。仮の言語を決め、話し手の交代に話し中に気づく。
 * - 文の全体：文が終わったとき。文の話し手を決め、その言語を指定して文を書き起こす。文の判定が、話し手の決まりの最後の機会になる。
 *
 * カードは次のどれかで確定する。
 * - 文が終わってから無音が続き、合わせて [SegmentationProfile.endSilenceSeconds] になった（話し終わり）
 * - 文の全体の話し手が、カードの確かな話し手と違った（話し手の交代。その文の頭で区切る）
 * - 話している最中に、カードの確かな話し手と違う言語の区間が [SegmentationProfile.minTurnSeconds] 以上の音声にわたって続き、
 *   その交代の境界が見つかった（話し手の交代・話し中。境界の位置で区切る）
 * - [SegmentationProfile.splitAfterSeconds] 話し続けた（長い発話）
 * - 利用者が区切った（[cut]）
 * カードの話し手と交代は、区間と文の根拠から話し手の時系列（[SpeakerTrack]）が決める。境界を探している間は、話し終わりと
 * 長い発話でカードを閉じない（閉じると、見つかった交代を区切れず、新しい話し手の音声が前の話し手の言語で書き起こされる）。
 * 話し終わりは位置を覚えておき、境界の結果を受けてから閉じる（[closeAfterSeam]）。文の判定は、届いていない区間があれば
 * その区間の後に渡す（判定のワーカーでは文のスコアが区間より先に処理されるので、届く順で使う根拠が変わらないようにする）。
 *
 * 認識は待たない：書き起こしとスコアはジョブ（[RecognitionJob]）として [submit] に出し、結果を [onResult] で受け取ってから
 * 次を決める。音声の処理（VAD・区切り）は、重い書き起こしの間も止まらない（2026-11-15、作者の試用で、書き起こし中の表示が
 * 5〜7秒続いた。1本のスレッドで認識を待っていたので、1回の重い書き起こしの後ろに音声と区切りが並んでいた）。
 * 位置は聞き始めからのサンプル数で覚え、結果が届いた時点のためた音声の位置に直す。
 *
 * 出来事はカードの番号で出す（[SpeechEvent]）。開く・閉じる・途中の文は、その場で出す（区切れるか・話し中かは今の状態なので、
 * 前のカードの書き起こしを待たない）。確定と止め終わりはカードの順に出す（[CardEvents]）。確定に要るジョブは途中結果より先に
 * 処理される（[RecognitionQueue]）ので、待たせる時間はほとんど無い。
 *
 * 話している間は、まだ終わっていない文を、カードの言語で書き起こして途中結果を出す（書き起こした文につなぐ。疑似ストリーミング）。
 * カードの言語が決まるまでは書き起こさず、決まったら文字より先に知らせる（空の途中結果）。確定は、書き起こした文と、
 * 区切る位置までの残りをつなぐ。ジョブの出し方と、音声認識モデルの性質による違い（渡す音声・頼む間隔）は [RecognitionRequests]。
 *
 * カードの表示の言語（途中結果と確定の言語）は、話し手の時系列の [SpeakerTrack.label]。文・区切った残り・途中結果の書き起こしの
 * 言語のうち、認識器が音声から判定したもの（[LanguageSource.JUDGED]）も根拠として時系列に渡す（[noteTranscript]）。
 *
 * 確定を出すカードの音声は [retain] に渡す（書き起こし直しのための取り置きは [UtteranceRetention] が持つ）。
 *
 * 判断は部品に分ける：話し手は [SpeakerJudge]、区切る位置は [CutPoint]、認識した文字の聞き違いの判定は [TranscriptCleanup]、
 * ジョブの順番は [RecognitionQueue]、確定を出す順番は [CardEvents]、ジョブの出し方は [RecognitionRequests]、
 * 文の書き起こしをつなぐのは [CardTranscript]。状態を持つので、すべての呼び出しは1本のスレッドから行う（[SpeechPipeline]）。
 * [trace] はセグメンターの経過を書く口（話した言葉を含むので、渡す側が詳しい記録を残すとき（デバッグ版の既定、正式版は
 * 詳細設定で選ぶ）だけ書く）。
 */
class SpeechSegmenter(
    private val vad: VoiceActivity,
    /** 書き起こさずにスコアを出せる認識器か（[SpeechRecognizer.identifies]）。出せればスコアは識別のワーカーに回す。 */
    identifies: Boolean,
    private val profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    /** 書き起こしの重いモデルか（[SpeechModel.heavy]）。声の始まりより前を渡さず、途中結果の間隔を詰める。 */
    heavy: Boolean = false,
    private val emit: (SpeechEvent) -> Unit,
    submit: (RecognitionJob) -> Unit,
    private val trace: (() -> String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    /** 確定を出すカードの番号（[Utterance.id]）と音声。確定の出来事より先に呼ぶ。 */
    private val retain: (Long, FloatArray) -> Unit = { _, _ -> },
    /** ジョブの番号の採番（同じエンジンの中でほかにジョブを出す部品と同じものを渡す）。 */
    jobIds: JobIds = JobIds(),
) {
    private val requests = RecognitionRequests(identifies, heavy, submit, clock, jobIds)

    private val prerollSamples = (profile.prerollSeconds * SAMPLE_RATE).toInt()
    private val splitAfterSamples = (profile.splitAfterSeconds * SAMPLE_RATE).toInt()

    /** 文の終わりで捨てる無音。0.2秒だけ残して語尾を切らないようにする。 */
    private val phraseTailDrop = ((profile.phraseSilenceSeconds - 0.2f) * SAMPLE_RATE).toInt()

    /** 文の終わりから、カードを確定させるまでに待つ無音。文の区切りと合わせて話し終わりの無音になる。 */
    private val cardEndGapSamples = ((profile.endSilenceSeconds - profile.phraseSilenceSeconds) * SAMPLE_RATE).toInt()

    /** 話し手が話したとみなす、同じ言語の根拠が続く音声の長さ（[SpeakerTrack]）。 */
    private val minTurnSamples = (profile.minTurnSeconds * SAMPLE_RATE).toLong()

    /** カードの言語を最初に判定する、話し始めからの音声の長さ。 */
    private val cardLangSamples = (profile.cardLanguageSeconds * SAMPLE_RATE).toInt().coerceAtLeast(MIN_PARTIAL_SAMPLES)

    /** ためる音声の上限（念のため）。前に足す音声と区切りより長くしておく。 */
    private val buffer = SpeechBuffer(prerollSamples, prerollSamples + splitAfterSamples + SAMPLE_RATE * 6)

    @Volatile var languages = SpeechLanguages.ANY

    /** 聞き始めから受け取った音声のサンプル数。 */
    private var received = 0L

    /** ためている音声の先頭が、聞き始めから何サンプル目か。 */
    private var bufferStart = 0L

    private fun abs(pos: Int): Long = bufferStart + pos
    private fun rel(at: Long): Int = (at - bufferStart).toInt()

    /** ためた音声のうち、話し始めの前に足した分の長さ。カードの言語の区間と途中結果は、ここから後ろで測る。 */
    private var speechStart = 0

    /** 直前に終わった文の終わり（ためた音声の中の位置）。まだ無ければ -1。 */
    private var lastPhraseEnd = -1

    /** 文が終わってから次の文が始まるまでの間（無音）にいるか。 */
    private var inGap = false
    private var gapSamples = 0

    /** カードをまたぐ話し手（前のカードの話し手と、決められない音声に使う言語）。 */
    private val handoff = SpeakerHandoff()

    /** 前の途中結果を頼んでから、話している音声がどれだけ増えたか。 */
    private var sinceLastPartial = 0

    /** 前に区間のスコアを頼んでから、話している音声がどれだけ増えたか。 */
    private var sinceLastWatch = 0

    /** 直前に届いた途中結果（後処理の後）と、それを書き起こした音声の始まり。区切る位置の候補に使う。 */
    private var lastPartial: Recognition? = null
    private var lastPartialStart = 0L

    /** 区切ると決めたカード。書き起こしがすべて届いたら確定を出す（[CardTranscript.final]）。[audio] はカードの音声（[retain] に渡す）。 */
    private class Closing(
        val start: Long,
        val end: Long,
        val reason: String,
        val startedAt: Long,
        val audio: FloatArray,
    )

    /** カード。[start] は聞き始めからの位置。 */
    private class Card(val id: Long, val start: Long, minTurn: Long, val fixedLanguage: String?) {
        /** 文の書き起こしと途中結果。 */
        val text = CardTranscript(start)
        var closing: Closing? = null

        /** 話し手の時系列。カードの話し手と、話し手が代わった位置は、ここだけが決める。 */
        val track = SpeakerTrack(start, minTurn)

        /** 話し手の言語（[SpeakerJudge] の組）。決まるまで null。 */
        val lang: String? get() = track.lang

        var partialInFlight = false
        var watchPending = false
        var seamPending = false

        /** 届いていない区間の後に渡す文の判定。 */
        var afterWatch: (() -> Unit)? = null

        /** 境界を探している間に話し終わった位置（聞き始めから）。境界の結果を受けてから閉じる。 */
        var speakingEndAt: Long? = null
    }

    /** 確定をカードの順に出す。 */
    private val events = CardEvents(emit)

    /** 音声をためているカード。話していなければ null。 */
    private var open: Card? = null
    private var nextCard = 0L

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
            openCard()
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
                if (gapSamples >= cardEndGapSamples && card.seamPending) {
                    if (card.speakingEndAt == null) card.speakingEndAt = abs(buffer.size)
                } else if (gapSamples >= cardEndGapSamples) {
                    // 話し終わり：書き起こした文をつなぐ（後ろは無音。重い書き起こしで確定を待たせない）。
                    // 短すぎて文として書き起こさなかった最後の文だけは、残りとして書き起こす
                    close(card, buffer.size, "話し終わり", restEnd = lastPhraseEnd)
                    buffer.clear()
                    return
                }
            }
        }

        // 長さは話し始めから数える（前に足した音声の分だけ早く区切らない）
        if (buffer.size - speechStart >= splitAfterSamples && !card.seamPending) {
            // 長く話し続けている：文の切れ目（無ければ静かなところ）で区切り、後ろの音声は次のカードの頭として残す。話し手は同じ
            val at = CutPoint.longUtterance(
                buffer,
                breakFrom = buffer.size - SPLIT_BREAK_SEARCH_SAMPLES,
                quietFrom = buffer.size - SPLIT_SEARCH_SAMPLES,
                to = buffer.size - MIN_TAIL_SAMPLES,
                breaks = cutBreaks(card),
            )
            cutAt(at, "長い発話", lang = card.lang, sure = card.track.sure)
        } else {
            sinceLastPartial += samples.size
            // 区間は話している音声だけで数える（無音の区間は、識別がどの言語とも言い切ることがある）
            if (!inGap) sinceLastWatch += samples.size
            if (sinceLastWatch >= requests.watchInterval && !inGap) {
                sinceLastWatch = 0
                watchLanguage(card)
            }
            if (sinceLastPartial >= requests.partialInterval && buffer.size - speechStart >= MIN_PARTIAL_SAMPLES) {
                sinceLastPartial = 0
                requestPartial(card)
            }
        }
    }

    /** 頼んだジョブの結果。届いた時点で、まだ意味のある結果だけを使う（閉じたカードの途中結果などは捨てる）。 */
    fun onResult(id: Long, output: RecognitionOutput) {
        if (closed) return
        requests.onResult(id, output)
    }

    /** 残っている音声を確定させる。すべてのカードを確定し終えたら [SpeechEvent.Flushed] を出す。 */
    fun flush() {
        if (closed) return
        vad.flush()
        open?.let { card ->
            close(card, buffer.size, "停止", restEnd = buffer.size)
            buffer.clear()
        }
        events.afterAll(SpeechEvent.Flushed)
    }

    /** 話している分を確定させ、続く音声は次のカードにする。話していなければ何もしない。 */
    fun cut() {
        if (closed) return
        if (!buffer.active || open == null) return
        cutAt(buffer.size, "手で区切り")
    }

    fun reset() {
        if (closed) return
        vad.reset()
        buffer.clear()
        requests.clear()
        events.clear()
        open = null
        received = 0
        bufferStart = 0
        speechStart = 0
        handoff.reset()
        requests.resetPacing()
        startSpeech()
    }

    /** 今カードを持っているか（ためた音声か、確定待ちのカードがある）。 */
    val idle: Boolean get() = events.idle

    /** 今音声をためているカード（[RecognitionQueue.dropStale] に渡す）。 */
    val openCardId: Long? get() = open?.id

    fun close() {
        if (closed) return
        closed = true
        requests.clear()
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

    private fun openCard(): Card = Card(nextCard++, bufferStart, minTurnSamples, languages.specified).also {
        events.opened(it.id)
        open = it
        emit(SpeechEvent.CardOpened(it.id))
        it.fixedLanguage?.let { lang -> setCardLang(it, lang, sure = true) }
    }

    /** 決められない音声に使う言語：カードの言語、前の話し手、自分の言語の順。 */
    private fun fallbackLang(card: Card): String? = handoff.fallback(card.lang, languages)

    /**
     * 文が終わった。[end] までのまだ書き起こしていない音声を1つの文にし、文の全体で話し手を決めてから書き起こす（[judgePhrase]）。
     * 短すぎる文は次の文と一緒にする。[lang] があれば、話し手は決まっている（区切った前のカードで決めた文）。
     */
    private fun phraseEnded(card: Card, end: Int, lang: String? = null) {
        val from = stitchedEnd(card)
        if (end - from < MIN_PARTIAL_SAMPLES) return
        val phrase = Phrase(abs(from), abs(end), lang ?: card.fixedLanguage, capture(from, end))
        card.text.add(phrase)
        if (phrase.lang != null) {
            transcribePhrase(card, phrase, null)
            return
        }
        scores(card, phrase.from, phrase.end, RecognitionPriority.PHRASE_LANGUAGE, PURPOSE_PHRASE) { scores, recognition ->
            if (phrase !in card.text) return@scores
            val judge = {
                if (phrase in card.text) judgePhrase(card, phrase, SpeakerJudge.decide(scores, languages, SpeakerJudge.PHRASE), recognition)
            }
            val waiting = card.afterWatch
            when {
                !card.watchPending -> judge()
                waiting == null -> card.afterWatch = judge
                else -> card.afterWatch = { waiting(); judge() }
            }
        }
    }

    /**
     * 文の話し手が [judged] と決まった（決められなければ null）。話し手の時系列（[SpeakerTrack.phrase]）が交代と決めれば
     * 文の頭で区切り、文は次のカードで書き起こす。そうでなければ文をカードの話し手の言語で書き起こす。
     * 閉じると決めたカードでは区切らない（確定を待たせない）。
     */
    private fun judgePhrase(card: Card, phrase: Phrase, judged: String?, recognition: Recognition?) {
        if (follow(card, card.track.phrase(phrase.from, phrase.end, judged), phrase)) return
        phrase.lang = card.lang ?: fallbackLang(card)
        transcribePhrase(card, phrase, recognition)
    }

    /**
     * 文を話し手の言語で書き起こす。スコアを出すのに書き起こした認識器（SenseVoice）は、その書き起こし（[recognition]）を使う
     * （言語を指定できないので、書き起こし直しても同じ）。
     */
    private fun transcribePhrase(card: Card, phrase: Phrase, recognition: Recognition?) {
        fun done(r: Recognition) {
            phrase.recognition = r
            noteTranscript(card, phrase, r)
            if (card === open) showStitched(card)
            finishIfReady(card)
        }
        if (recognition != null) {
            trace { "書き起こし（文・スコアのもの）${at(phrase.from)}〜${at(phrase.end)}: [${recognition.lang}] ${recognition.text}" }
            done(recognition)
            return
        }
        transcribe(card, phrase, RecognitionPriority.PHRASE, "文", phrase.lang) { r -> done(r) }
    }

    /** 書き起こした（頼んだ）文の終わり（ためた音声の中の位置）。ここから後ろが、まだ書き起こしていない音声。 */
    private fun stitchedEnd(card: Card): Int = rel(card.text.stitchedEnd).coerceAtLeast(0)

    /**
     * 話している最中の区間のスコアで、カードの言語を決め、話し手の交代を監視する。カードの言語がまだ無ければ、話し始めからの音声
     * （[SegmentationProfile.cardLanguageSeconds] から）で決める。決まったら直近1.5秒を見る。区間の言語から、カードの言語・
     * 交代（境界を探す [findSeam]）を決めるのは話し手の時系列（[SpeakerTrack.window]）。
     * 話し始めから長く話してもカードの言語が決まらなければ、前の話し手の言語を仮に使う（決まらないと途中結果を出せない）。
     */
    private fun watchLanguage(card: Card) {
        if (card.fixedLanguage != null) return
        if (buffer.size < speechStart + cardLangSamples) return
        if (card.watchPending || card.seamPending) return
        handoff.provisional(card.track, buffer.size - speechStart, languages)?.let { setCardLang(card, it, sure = false) }
        val from = if (card.lang == null) speechStart else (buffer.size - LANG_WINDOW_SAMPLES).coerceAtLeast(speechStart)
        val fromAbs = abs(from)
        val toAbs = abs(buffer.size)
        card.watchPending = true
        scores(card, fromAbs, toAbs, RecognitionPriority.LANGUAGE, PURPOSE_WATCH, paced = true) { scores, _ ->
            card.watchPending = false
            if (card === open) follow(card, card.track.window(fromAbs, toAbs, SpeakerJudge.decide(scores, languages, SpeakerJudge.WINDOW)))
            card.afterWatch?.let {
                card.afterWatch = null
                it()
            }
        }
    }

    /**
     * 話し手の時系列の判定を実行する（閉じたカードの残りの判定のほかは、ここだけで実行する）。文の頭で区切る判定（[SpeakerTrack.Verdict.Cut]）は
     * 文の全体の判定（[phrase] の文）からだけ来る。決めた話し手を文に持たせて次のカードへ渡す（同じ音声をもう一度判定しない）。
     * 区切ったら true（文は次のカードで書き起こす）。
     */
    private fun follow(card: Card, verdict: SpeakerTrack.Verdict, phrase: Phrase? = null): Boolean {
        when (verdict) {
            is SpeakerTrack.Verdict.Assign -> langChanged(card)
            is SpeakerTrack.Verdict.Seek -> findSeam(card, verdict)
            is SpeakerTrack.Verdict.Cut -> {
                phrase?.lang = verdict.lang
                cutAt(rel(verdict.at), "話し手の交代（文：${card.lang}→${verdict.lang}）", lang = verdict.lang, sure = true)
                return true
            }
            SpeakerTrack.Verdict.Keep -> Unit
        }
        return false
    }

    /** カードの言語を決める（前のカードから引き継ぐ・決められないときの仮）。 */
    private fun setCardLang(card: Card, lang: String, sure: Boolean) {
        if (card.track.assign(lang, sure)) langChanged(card)
    }

    /** カードの言語が変わった。まだ文字の出ていないカードなら、空の途中結果で言語を先に伝え、すぐ書き起こす。 */
    private fun langChanged(card: Card) {
        val lang = card.lang ?: return
        trace { "カードの言語 $lang（${if (card.track.sure) "確か" else "仮"}）" }
        if (card !== open) return
        // 仮の言語で書き起こした途中結果は古いので、決めた言語で書き起こし直す
        card.text.dropPartial()
        if (card.text.shown.isEmpty()) emit(SpeechEvent.CardText(card.id, "", card.track.label()))
        sinceLastPartial = 0
        requestPartial(card)
    }

    /**
     * 言語が代わった位置を探して区切る。話し手の時系列が示した範囲（カードの話し手だと分かっている最後の根拠から、新しい言語の
     * 連続区間の最初の区間の終わりの、調べる区間1つ分後ろまで）を0.25秒ずつずらした1秒の区間のうち、最初に新しい言語になったところを境界とみなし、
     * その前後の区切る位置（[CutPoint.languageSeam]）で区切る。区間は二分探索で選ぶ（[RecognitionJob.Seam]）。見つからなければ
     * 区切らない（区間の聞き違い）。静かさだけで探すと、話し手の境界に間が無いとき、新しい言語の文の中の小さな間を選び、
     * 新しい言語の最初の語が前のカードに入って消えた。
     */
    private fun findSeam(card: Card, seek: SpeakerTrack.Verdict.Seek) {
        val newLang = seek.lang
        card.seamPending = true
        val from = rel(seek.from).coerceIn(0, (buffer.size - LANG_PROBE_SAMPLES).coerceAtLeast(0))
        // 交代は連続区間の最初の区間の終わりより前にある。新しい言語の最初の区間は、新しい言語が後ろ半分だけでもそう決まるので、
        // 調べる区間が交代の後ろに丸ごと入るよう、区間1つ分だけ後ろまで調べる
        val to = (rel(seek.to) + LANG_PROBE_SAMPLES).coerceIn(minOf(from + LANG_PROBE_SAMPLES, buffer.size), buffer.size)
        val fromAbs = abs(from)
        val previous = card.lang
        requests.seam(
            card.id, buffer.slice(from, to), abs(to), PURPOSE_SEAM, languages, newLang, LANG_PROBE_SAMPLES, LANG_PROBE_STEP_SAMPLES,
        ) { seam ->
            card.seamPending = false
            if (card !== open) return@seam
            trace { "境界 ${seam?.let { at(fromAbs + it) } ?: "なし"}（$previous→$newLang）" }
            if (seam == null) {
                card.track.seamMissed()
            } else {
                val s = rel(fromAbs) + seam
                val at = CutPoint.languageSeam(buffer, s - CutPoint.FRAME_SAMPLES * 2, s + CutPoint.FRAME_SAMPLES * 6)
                // 後ろの話し手は、確かな連続区間で決まっている
                cutAt(at, "話し手の交代（話し中：$previous→$newLang）", lang = newLang, sure = true)
            }
            closeAfterSeam(card)
        }
    }

    /**
     * 境界を探している間に話し終わっていたら、その位置で閉じる。まだ無音が続いていれば、次の音声の処理で話し終わりとして閉じる。
     * もう次の発話が始まっていれば、話し終わりの位置で区切り、次の発話は次のカードにする。
     */
    private fun closeAfterSeam(card: Card) {
        val endAt = card.speakingEndAt ?: return
        card.speakingEndAt = null
        if (inGap || open == null) return
        cutAt(rel(endAt).coerceIn(0, buffer.size), "話し終わり")
    }

    /**
     * 話し続けたまま [at] で区切る。前を確定させ、後ろは次のカードの頭として残す。[lang] は後ろの話し手（分かっていれば）。
     * 区切る位置より後ろで終わった文は、次のカードの文として書き起こし直す（前のカードからは外すので、移さないと言葉が消える）。
     * 区切る位置から始まる文は、決めた話し手をそのまま使う。文を移してから次のカードの言語を決める（言語が決まると途中結果を
     * 頼むので、先に決めると、移す文を含む音声を途中結果として書き起こし、文を足した時点で捨てることになる）。
     */
    private fun cutAt(at: Int, reason: String, lang: String? = null, sure: Boolean = false) {
        val card = open ?: return
        val cutAbs = abs(at)
        val moved = card.text.phrases.filter { it.end > cutAbs }.map { p -> p.end to p.lang?.takeIf { p.from >= cutAbs } }
        close(card, at, reason, restEnd = at)
        handoff.ended(card.lang)
        buffer.take(at)
        bufferStart += at
        speechStart = (speechStart - at).coerceAtLeast(0)
        lastPhraseEnd = if (lastPhraseEnd > at) lastPhraseEnd - at else -1
        resetCardState()
        val next = openCard()
        moved.forEach { (end, known) -> phraseEnded(next, rel(end), known) }
        if (lang != null) setCardLang(next, lang, sure)
    }

    /**
     * カードを [at]（ためた音声の中の位置）で区切ると決める。書き起こした文をつなぎ、[restEnd] までのまだ書き起こしていない
     * 音声（区切る位置が文の途中なら、その文の頭から）を、カードの話し手の言語で書き起こす。話し手は話し手の時系列が決めたもので、
     * 残りだけで決め直さない（2026-11-15 の beta.3 の試用で、交代で区切った残りを決め直して新しい話し手の言語になり、
     * 前の話し手の文を違う言語で書き起こした）。カードの言語がまだ無いときだけ、残りのスコアを話し手の時系列に渡して決める。
     * 閉じたことはその場で出し（[SpeechEvent.CardClosed]）、書き起こしがすべて届いたら確定を出す（[finishIfReady]）。
     */
    private fun close(card: Card, at: Int, reason: String, restEnd: Int) {
        val start = card.start
        val end = abs(at)
        card.track.close()
        card.text.cut(end)
        val from = card.text.stitchedEnd
        val restTo = abs(restEnd).coerceAtMost(end)
        val rest = if (restTo - from >= MIN_REST_SAMPLES) Phrase(from, restTo, card.lang, capture(rel(from), rel(restTo))) else null
        if (rest != null) {
            fun done(r: Recognition) {
                rest.recognition = r
                noteTranscript(card, rest, r)
                finishIfReady(card)
            }
            if (rest.lang != null) {
                transcribe(card, rest, RecognitionPriority.FINAL, "確定の残り", rest.lang) { done(it) }
            } else scores(card, rest.from, rest.end, RecognitionPriority.PHRASE_LANGUAGE, PURPOSE_REST) { scores, recognition ->
                // 閉じた後の判定は区切らせない（[SpeakerTrack.close]）。カードの言語を決める判定（Assign）は返るが、閉じたカードには
                // 途中結果を出さないので実行しない（[follow] を通すと、出さない言語の変化を記録に書く）
                card.track.phrase(rest.from, rest.end, SpeakerJudge.decide(scores, languages, SpeakerJudge.PHRASE))
                rest.lang = card.lang ?: fallbackLang(card)
                if (recognition != null) done(recognition)
                else transcribe(card, rest, RecognitionPriority.FINAL, "確定の残り", rest.lang) { done(it) }
            }
        }
        card.text.close(rest)
        card.closing = Closing(start, end, reason, clock(), capture(rel(start).coerceAtLeast(0), at))
        if (open === card) open = null
        trace { "区切ると決めた（$reason）${at(start)}〜${at(end)}" }
        emit(SpeechEvent.CardClosed(card.id))
        finishIfReady(card)
    }

    /**
     * 区切ると決めたカードの書き起こしがすべて届いていれば、確定を出す。言語はカードの表示の言語（[SpeakerTrack.label]。
     * 決まっていなければ書き起こしの言語）。何も聞き取れなければ発話の無い確定を出す（カードが残らないように）。
     * 発話のあるカードの音声は [retain] に渡す。
     */
    private fun finishIfReady(card: Card) {
        val closing = card.closing ?: return
        if (events.finished(card.id)) return
        val r = card.text.final() ?: return
        val label = card.track.label(closing.end)
        val lang = label ?: r.lang
        val waitedMs = (clock() - closing.startedAt) / 1_000_000
        if (label != card.lang) trace { "カードの表示の言語 $label（書き起こしの言語。区間と文の全体では ${card.lang}）" }
        trace { "確定（${closing.reason}）${at(closing.start)}〜${at(closing.end)}: [$lang] ${r.text} ／ 直前の途中 ${card.text.shown}（区切ってから${waitedMs}ms）" }
        val utterance = r.text.takeIf { it.isNotEmpty() }?.let { text ->
            Utterance(
                id = card.id,
                text = text,
                lang = lang,
                startMs = closing.start.coerceAtLeast(0) * 1000 / SAMPLE_RATE,
                durationMs = (closing.end - closing.start) * 1000 / SAMPLE_RATE,
                recognizeMs = waitedMs,
            )
        }
        if (utterance != null) retain(card.id, closing.audio)
        // 話し終わりで閉じたカードの話し手は、次に話し始めたカードの「前の話し手」
        if (open == null) handoff.ended(card.lang)
        events.finish(card.id, SpeechEvent.CardFinished(card.id, utterance))
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
        card.partialInFlight = true
        transcribe(card, phrase, RecognitionPriority.PARTIAL, "途中", lang, paced = true) { r ->
            card.partialInFlight = false
            if (card !== open || card.lang != lang) return@transcribe
            noteTranscript(card, phrase, r)
            lastPartial = r
            lastPartialStart = fromAbs
            card.text.partial(fromAbs, r)
            showStitched(card)
        }
    }

    /**
     * 書き起こした文と途中結果をつないで（[CardTranscript.current]）知らせる。同じ文字を続けて出さない（話し終わってから
     * 区切るまでの無音の間は同じ文字が続く）。言語はカードの表示の言語（[SpeakerTrack.label]）。
     */
    private fun showStitched(card: Card) {
        val r = card.text.current() ?: return
        if (!card.text.show(r.text)) return
        emit(SpeechEvent.CardText(card.id, r.text, card.track.label() ?: r.lang))
    }

    /**
     * カードの [phrase] の範囲を書き起こした結果を、話し手の時系列に書き起こしの言語の根拠として渡す。根拠になるのは、
     * 認識器が音声から判定した言語（[LanguageSource.JUDGED]）だけ。指定した言語で書き起こした結果（Qwen3-ASR）の言語は
     * 指定の写しなので、根拠にしない（同じ頭の前の根拠は消える）。
     */
    private fun noteTranscript(card: Card, phrase: Phrase, r: Recognition) {
        val judged = LanguageEvidence.of(r, languages)?.takeIf { r.source == LanguageSource.JUDGED }
        card.track.transcribed(phrase.from, phrase.end, judged)
    }

    /**
     * 長い発話を区切る位置の候補（文の切れ目）。書き起こした文の終わり（VAD の文の区切り）と、
     * 直前の途中結果の切れ目を、ためた音声の中の位置に直す。
     */
    private fun cutBreaks(card: Card): List<SpeechBreak> {
        val offset = rel(lastPartialStart)
        val partialBreaks = lastPartial?.breaks.orEmpty().map { it.copy(at = it.at + offset) }
        return card.text.phrases.map { SpeechBreak(rel(it.end), sentence = true) } + partialBreaks
    }

    // ---- ジョブと出来事 ----

    /**
     * [phrase] の音声を [language] として書き起こしてもらう（識別を省く）。null なら識別してもらい、識別できなければ
     * 前の話し手の言語として書き起こす。
     */
    private fun transcribe(
        card: Card,
        phrase: Phrase,
        priority: RecognitionPriority,
        purpose: String,
        language: String?,
        paced: Boolean = false,
        then: (Recognition) -> Unit,
    ) {
        val samples = phrase.audio ?: capture(rel(phrase.from), rel(phrase.end))
        requests.transcribe(card.id, samples, phrase.end, priority, purpose, languages, language, fallbackLang(card), paced) { r ->
            trace { "書き起こし（$purpose）${at(phrase.from)}〜${at(phrase.end)} 言語の指定 $language: [${r.lang}] ${r.text}" }
            then(r)
        }
    }

    /** ためた音声の [from]〜[to] の写し（認識に渡す。始まりは [RecognitionRequests.captureStart]）。 */
    private fun capture(from: Int, to: Int): FloatArray {
        val end = to.coerceIn(0, buffer.size)
        return buffer.slice(requests.captureStart(buffer, from, end, speechStart), end)
    }

    /**
     * 聞き始めからの位置 [from]〜[to] の音声の、会話の言語ごとのスコアを出してもらう。短すぎればすぐ null を返す。
     * 書き起こしてスコアを出した認識器は、その書き起こし（後処理の後）も返す。
     */
    private fun scores(
        card: Card,
        from: Long,
        to: Long,
        priority: RecognitionPriority,
        purpose: String,
        paced: Boolean = false,
        then: (Map<String, Float>?, Recognition?) -> Unit,
    ) {
        val start = rel(from).coerceAtLeast(0)
        val end = rel(to).coerceAtMost(buffer.size)
        if (end - start < MIN_PARTIAL_SAMPLES) {
            then(null, null)
            return
        }
        requests.scores(card.id, buffer.slice(start, end), to, priority, purpose, languages, paced) { scored, recognition ->
            trace { "スコア（$purpose）${at(from)}〜${at(to)}: ${scored?.scores?.entries?.sortedByDescending { it.value }?.take(3)?.joinToString { "%s=%.2f".format(it.key, it.value) }}" }
            then(scored?.scores, recognition)
        }
    }

    /** 聞き始めからの位置を秒で書く（ログ用）。 */
    private fun at(pos: Long): String = "%.2f秒".format(pos.coerceAtLeast(0) / SAMPLE_RATE.toDouble())

    companion object {
        /** 短すぎる音声は認識しても空になりやすいので、0.4秒たまってから認識する。 */
        private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** 区切るときに、文として書き起こしていない残りを書き起こす最短の長さ（短い「はい」を落とさない）。 */
        private const val MIN_REST_SAMPLES = SAMPLE_RATE / 4

        /** 話している最中の区間に使う、直近の音声の長さ。 */
        private const val LANG_WINDOW_SAMPLES = SAMPLE_RATE * 3 / 2

        /** 言語の境界を探すときに、1回に判定する長さと、ずらす幅。 */
        private const val LANG_PROBE_SAMPLES = SAMPLE_RATE
        private const val LANG_PROBE_STEP_SAMPLES = SAMPLE_RATE / 4

        /**
         * 長い発話を区切る位置を探す範囲。文の切れ目は直近6秒（カードが読める長さ（約12秒）より短くなりすぎない範囲で、
         * 文の終わりを見つけやすくする）、切れ目が無いときの静かなところは直近4秒。区切った後ろに残す最小の長さ。
         */
        private const val SPLIT_BREAK_SEARCH_SAMPLES = SAMPLE_RATE * 6
        private const val SPLIT_SEARCH_SAMPLES = SAMPLE_RATE * 4
        private const val MIN_TAIL_SAMPLES = SAMPLE_RATE * 3 / 10

        /** スコアを頼む目的（ログに出す）。区間のスコアはカードごとに1件ずつ頼む。 */
        private const val PURPOSE_WATCH = "話し中の区間"
        private const val PURPOSE_PHRASE = "文の全体"
        private const val PURPOSE_REST = "区切った残り"
        private const val PURPOSE_SEAM = "境界探し"
    }
}
