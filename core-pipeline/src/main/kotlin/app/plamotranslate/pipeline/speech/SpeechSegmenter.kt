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
 * - 話している最中に、直近の言語が続けてカードの言語と違った（話し手の交代・間なし）
 * - [SegmentationProfile.splitAfterSeconds] 話し続けた（長い発話）
 * - 利用者が区切った（[cut]）
 *
 * 認識は待たない：書き起こしと言語の判定は仕事（[RecognitionJob]）として [submit] に出し、結果を [onResult] で受け取ってから
 * 次を決める。音声の処理（VAD・区切り）は、重い書き起こしの間も止まらない（2026-11-15、作者の試用で、書き起こし中の表示が
 * 5〜7秒続いた。1本のスレッドで認識を待っていたので、1回の重い書き起こしの後ろに音声と区切りが並んでいた）。
 * 位置は聞き始めからのサンプル数で覚え、結果が届いた時点のためた音声の位置に直す。
 *
 * 出来事はカードの順に出す：前のカードの確定が届くまで、次のカードの出来事は [Card.held] に持っておく。
 * 確定に要る書き起こしは途中結果より先に処理される（[RecognitionQueue]）ので、待たせる時間はほとんど無い。
 *
 * 話している間は、ためた音声を認識し直して途中結果を出す（疑似ストリーミング）。書き起こしが重いモデル（[byPhrase]、Qwen3-ASR）は、
 * 文が終わるたびにその文を1回書き起こして覚え、途中結果は「覚えた文＋まだ終わっていない文」、確定は「覚えた文＋区切る位置までの残り」
 * にする。言語の見張りは書き起こさずに言語の識別だけで行い（[identifies]）、書き起こしにはカードの言語を渡して識別を省く。
 * カードの言語は話し始めの [SegmentationProfile.cardLanguageSeconds] で決め、文字より先に知らせる（空の途中結果）。
 * 書き起こしの時間は音声の長さに比例するので、話し始めの前に足した音声は、声の始まり（[SpeechOnset]）から渡す。
 *
 * 判断は部品に分ける：言語が話し手の交代の根拠になるかは [LanguageEvidence]、区切る位置は [CutPoint]、
 * 認識した文字の聞き違いの判定は [TranscriptCleanup]、仕事の順番は [RecognitionQueue]。
 * 状態を持つので、すべての呼び出しは1本のスレッドから行う（[SpeechPipeline]）。[trace] は区切りの経過を書く口
 * （話した言葉を含むので、デバッグ版だけが書く）。
 */
class SpeechSegmenter(
    private val vad: VoiceActivity,
    /** 言語だけを書き起こさずに判定できる認識器か（[SpeechRecognizer.identifies]）。できれば言語の判定は識別の担当に回す。 */
    private val identifies: Boolean,
    private val profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    /** 文ごとに書き起こしてつなぐ（書き起こしの重いモデル。[SpeechModel.byPhrase]）。 */
    private val byPhrase: Boolean = false,
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

    /** カードの言語を決める、カードの最初の音声の長さ。 */
    private val cardLangSamples = (profile.cardLanguageSeconds * SAMPLE_RATE).toInt().coerceAtLeast(MIN_PARTIAL_SAMPLES)

    /** ためる音声の上限（念のため）。前に足す音声と区切りより長くしておく。 */
    private val buffer = SpeechBuffer(prerollSamples, prerollSamples + splitAfterSamples + SAMPLE_RATE * 6)

    /** 言語の判定を回す担当。 */
    private val languageLane = if (identifies) RecognitionLane.IDENTIFY else RecognitionLane.TRANSCRIBE

    @Volatile var languages = SpeechLanguages.ANY

    private fun evidence(r: Recognition?): String? = r?.let { LanguageEvidence.of(it, languages) }

    /** 聞き始めから受け取った音声のサンプル数。 */
    private var received = 0L

    /** ためている音声の先頭が、聞き始めから何サンプル目か。 */
    private var bufferStart = 0L

    private fun abs(pos: Int): Long = bufferStart + pos
    private fun rel(at: Long): Int = (at - bufferStart).toInt()

    /** ためた音声のうち、話し始めの前に足した分の長さ。カードの言語の窓と途中結果は、ここから後ろで測る。 */
    private var speechStart = 0

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

    /** 次の文の頭では言語を判定できなかった（文が終わったら、文の全体で確かめ直す）。 */
    private var switchUndecided = false

    /** このカードの言語（[LanguageEvidence] の組）。言語の交代を見張る基準。 */
    private var cardLang: String? = null

    /** 直近の音声の言語が、カードの言語と続けて何回違ったか。 */
    private var mismatches = 0

    /** 前の途中結果を頼んでから、話している音声がどれだけ増えたか。 */
    private var sinceLastPartial = 0

    /** 前に言語を見張ってから、話している音声がどれだけ増えたか。 */
    private var sinceLastWatch = 0

    /** 途中結果を頼む間隔。かかった時間の何倍かより短くはしない（CPU を途中結果で埋めない）。 */
    private var partialInterval = MIN_PARTIAL_INTERVAL

    /** 直前に届いた途中結果（後処理の後）と、それを書き起こした音声の始まり。言語の判定と区切る位置の候補に使う。 */
    private var lastPartial: Recognition? = null
    private var lastPartialStart = 0L

    /** 書き起こした（頼んだ）文。[from]〜[end] は聞き始めからの位置。結果が届くまで [recognition] は null。 */
    private class Phrase(val from: Long, val end: Long) {
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

        /** まだ終わっていない文の途中結果と、それを書き起こした音声の始まり。 */
        var tail: Recognition? = null
        var tailFrom = 0L

        /** 直前に出した途中結果の文字。 */
        var lastText = ""
        var partialInFlight = false
        var langPending = false

        /** カードの言語を判定した窓の終わり（判定できなかったら、音声が増えてから広い窓で判定し直す）。 */
        var langTriedTo = -1L
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
            val end = (buffer.size - phraseTailDrop).coerceAtLeast(phraseStart)
            // 次の文が、言語を確かめる長さに届かずに終わったら、文の全体で前の文と言語が同じか確かめる
            // 頭で判定できなかったときも、文の全体で確かめ直す（長い窓ほど判定できる。2026-11-15のベータで、韓国語の後の日本語の文を
            // 頭の1.7秒では判定できず、文の全体では日本語と判定できたのに、1枚のカードのままだった）
            if ((!switchChecked || switchUndecided) && end - phraseStart >= MIN_SWITCH_CHECK_SAMPLES) {
                switchChecked = true
                switchUndecided = false
                checkNextPhrase(card, end)
            }
            phraseEnded(card, end)
            lastPhraseEnd = end
            inGap = true
            gapSamples = 0
            trace { "文の終わり ${at(abs(end))}" }
        } else if (inGap) {
            if (signal.speaking) {
                // 次の文が始まった。1秒たまったら、前の文と言語が同じか確かめる
                inGap = false
                phraseStart = lastPhraseEnd
                switchChecked = false
                switchUndecided = false
            } else {
                gapSamples += samples.size
                if (gapSamples >= cardEndGapSamples) {
                    // 話し終わり：ためた音声の全体を確定させる。後ろは無音なので文の終わりで切る理由が無く、
                    // VADが話していないとみなした小さい声の語尾も入る（最後の途中結果と同じ音声になる）。
                    // 文ごとに書き起こすモデルは、書き起こした文をつなぐだけにする（後ろは無音。重い書き起こしで確定を待たせない）
                    close(card, buffer.size, "話し終わり", transcribeRest = !byPhrase, speakingEnd = true)
                    buffer.clear()
                    return
                }
            }
        }

        if (!switchChecked && buffer.size - phraseStart >= cardLangSamples) {
            switchChecked = true
            checkNextPhrase(card)
        }

        // 長さは話し始めから数える（前に足した音声の分だけ早く区切らない）
        if (buffer.size - speechStart >= splitAfterSamples) {
            // 長く話し続けている：文の切れ目（無ければ静かなところ）で区切り、後ろの音声は次のカードの頭として残す
            val at = CutPoint.longUtterance(
                buffer,
                breakFrom = buffer.size - SPLIT_BREAK_SEARCH_SAMPLES,
                quietFrom = buffer.size - SPLIT_SEARCH_SAMPLES,
                to = buffer.size - MIN_TAIL_SAMPLES,
                breaks = cutBreaks(card),
            )
            cutAt(at, "長い発話", manual = false)
        } else {
            sinceLastPartial += samples.size
            sinceLastWatch += samples.size
            val started = buffer.size - speechStart >= MIN_PARTIAL_SAMPLES
            // 言語の見張りは途中結果と別の間隔で見る。途中結果の間隔は書き起こしの重さで延びるが、見張りは言語の識別の
            // 担当で軽く動くので、延ばすと話し手の交代に気づくのが遅れ、継ぎ目が探す範囲の外に出ていた
            if (sinceLastWatch >= WATCH_INTERVAL_SAMPLES && started) {
                sinceLastWatch = 0
                watchLanguage(card)
            }
            if (sinceLastPartial >= partialInterval && started) {
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
            close(card, buffer.size, "停止", transcribeRest = true, speakingEnd = true)
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
        partialInterval = MIN_PARTIAL_INTERVAL
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

    /** 話し始めたときに、文の位置と言語の状態をまっさらにする。 */
    private fun startSpeech() {
        phraseStart = 0
        lastPhraseEnd = -1
        lastPhraseLang = null
        inGap = false
        gapSamples = 0
        switchChecked = true
        switchUndecided = false
        resetCardState()
    }

    private fun resetCardState() {
        cardLang = null
        mismatches = 0
        sinceLastPartial = 0
        sinceLastWatch = 0
        lastPartial = null
        lastPartialStart = 0
    }

    private fun openCard(): Card = Card(nextCard++, bufferStart).also {
        cards.addLast(it)
        open = it
    }

    /** 文が終わった。文ごとに書き起こすモデルは、ここで1回だけ書き起こして覚える。ほかのモデルは文の言語だけを見る。 */
    private fun phraseEnded(card: Card, end: Int) {
        if (byPhrase) {
            val from = stitchedEnd(card)
            // 短すぎる文は次の文と一緒に書き起こす
            if (end - from < MIN_PARTIAL_SAMPLES) return
            val phrase = Phrase(abs(from), abs(end))
            card.phrases += phrase
            transcribe(card, phrase, RecognitionPriority.PHRASE, "文") { r ->
                phrase.recognition = r
                // 中身のない文（雑音の聞き違い）は言語が無い。その場合は前の文の言語を引き継ぐ
                if (card === open) lastPhraseLang = evidence(r) ?: lastPhraseLang
                if (card === open) showStitched(card)
                finishIfReady(card)
            }
            return
        }
        val partial = lastPartial
        if (phraseStart == 0 && partial?.lang != null) {
            lastPhraseLang = evidence(partial) ?: lastPhraseLang
            return
        }
        language(card, phraseStart, end, PURPOSE_PHRASE) { lang ->
            if (card === open) lastPhraseLang = lang ?: lastPhraseLang
        }
    }

    /** 書き起こした（頼んだ）文の終わり（ためた音声の中の位置）。ここから後ろが、まだ書き起こしていない音声。 */
    private fun stitchedEnd(card: Card): Int = card.phrases.lastOrNull()?.let { rel(it.end) } ?: rel(card.start).coerceAtLeast(0)

    /**
     * 次の文の頭の言語が前の文と違えば、話し手が代わったとみなして前の文までで区切る（間あり）。窓はカードの言語を決める長さ
     * （文がそれより短ければ文の全体）。1秒の窓は、作者の日本語を韓国語と誤ることが多く（2026-11-15のベータの記録で、
     * 1秒の窓の判定32回のうち韓国語6回・会話の外17回）、誤った交代でカードの言語が変わると、その後の日本語も韓国語として書き起こした。
     */
    private fun checkNextPhrase(card: Card, end: Int = buffer.size) {
        val cutAbs = abs(lastPhraseEnd)
        val checkedFrom = phraseStart
        language(card, phraseStart, end, PURPOSE_NEXT_PHRASE) { lang ->
            if (card !== open) return@language
            // 頭で判定できなければ、同じ文が終わったときに文の全体で確かめ直す
            if (lang == null && phraseStart == checkedFrom) switchUndecided = true
            val prev = lastPhraseLang ?: cardLang.takeIf { byPhrase }
            if (lang == null || prev == null || lang == prev) return@language
            val at = rel(cutAbs)
            if (at <= 0 || at > buffer.size) return@language
            cutAt(at, "言語の交代（間あり：$prev→$lang）", manual = false, lang = lang)
        }
    }

    /**
     * 間を空けずに話し手が代わったときのため、話している最中も言語を見張る（確定の前に、誤ったカードの言語を直して見せる）。
     * カードの言語がまだ無ければ、話し始めの [SegmentationProfile.cardLanguageSeconds] で決める。決まったら、直近1.5秒の言語が
     * [SegmentationProfile.liveSwitchChecks] 回続けてカードの言語と違えば、話し手が代わったとみなし、言語の継ぎ目で区切る。
     * 1回だけで区切らないのは、外来語や固有名詞で言語の判定が一瞬揺れるため。
     */
    private fun watchLanguage(card: Card) {
        if (cardLang == null) {
            if (card.langPending || buffer.size < speechStart + cardLangSamples) return
            // 判定できなかったら、音声が増えてから話し始めからの広い窓で判定し直す（同じ窓は何度判定しても同じ）
            val end = minOf(buffer.size, speechStart + CARD_LANG_MAX_SAMPLES)
            if (card.langTriedTo >= 0 && abs(end) - card.langTriedTo < CARD_LANG_RETRY_SAMPLES) {
                // 一番広い窓でも判定できなければ、手がかりの言語にする（言語が決まらないと、文字を出せず言語の見張りも始まらない）
                if (end == speechStart + CARD_LANG_MAX_SAMPLES) setCardLang(lastPhraseLang ?: languages.primary ?: return)
                return
            }
            card.langPending = true
            card.langTriedTo = abs(end)
            language(card, speechStart, end, PURPOSE_CARD) { lang ->
                card.langPending = false
                if (card === open && cardLang == null && lang != null) setCardLang(lang)
            }
            return
        }
        if (buffer.size < speechStart + cardLangSamples + LANG_WINDOW_SAMPLES) return
        if (card.watchPending || card.seamPending) return
        card.watchPending = true
        val expected = cardLang
        language(card, buffer.size - LANG_WINDOW_SAMPLES, buffer.size, PURPOSE_WATCH) { recent ->
            card.watchPending = false
            if (card !== open || cardLang != expected) return@language
            // 判定できなかった窓は、交代の根拠にも反証にもしない（会話の外の言語と判定されることが多く、数え直すと交代を見逃した）
            if (recent == null) return@language
            if (recent == expected) {
                mismatches = 0
                return@language
            }
            if (++mismatches < profile.liveSwitchChecks) return@language
            findSeam(card, recent)
        }
    }

    /** カードの言語を決める。文ごとに書き起こすモデルで、まだ文字の出ていないカードなら、空の途中結果で言語を先に知らせ、すぐ書き起こす。 */
    private fun setCardLang(lang: String) {
        cardLang = lang
        val card = open ?: return
        trace { "カードの言語 $lang" }
        if (byPhrase && card.lastText.isEmpty()) {
            send(card, SpeechEvent.Partial("", lang))
            sinceLastPartial = 0
            requestPartial(card)
        }
    }

    /**
     * 言語が代わった位置を探して区切る。3.5秒前〜1秒前を0.25秒ずつずらした1秒の窓のうち、最初に [newLang] になったところを
     * 継ぎ目とみなし、その前後の区切る位置（[CutPoint.languageSeam]）で区切る。窓は二分探索で選ぶ（[RecognitionJob.Seam]）。
     * 静かさだけで探すと、話し手の継ぎ目に間が無いとき、新しい言語の文の中の小さな間を選び、新しい言語の最初の語が前のカードに入って消えた。
     */
    private fun findSeam(card: Card, newLang: String) {
        card.seamPending = true
        val from = (buffer.size - SWITCH_SEARCH_SAMPLES).coerceAtLeast(0)
        val to = buffer.size - SWITCH_SEARCH_END_SAMPLES
        val fromAbs = abs(from)
        val toAbs = abs(to)
        val previous = cardLang
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
            val start = rel(fromAbs).coerceAtLeast(0)
            val end = rel(toAbs)
            val seam = (out as? RecognitionOutput.SeamFound)?.at
            val at = if (seam != null) {
                val s = rel(fromAbs) + seam
                CutPoint.languageSeam(buffer, s - CutPoint.FRAME_SAMPLES * 2, s + CutPoint.FRAME_SAMPLES * 6)
            } else {
                CutPoint.languageSeam(buffer, start, end)
            }
            cutAt(at, "言語の交代（話し中：$previous→$newLang）", manual = false, lang = newLang)
        }
    }

    /** 話し続けたまま [at] で区切る。前を確定させ、後ろは次のカードの頭として残す。 */
    private fun cutAt(at: Int, reason: String, manual: Boolean, lang: String? = null) {
        val card = open ?: return
        // 区切る位置より後ろで終わった文（言語の判定が届く前に文が終わっていたもの）は、次のカードの文として書き起こし直す
        // （前のカードの言語で書き起こしているうえ、前のカードからは外すので、移さないと言葉が消える）
        val moved = if (byPhrase) card.phrases.filter { it.end > abs(at) }.map { it.end } else emptyList()
        close(card, at, reason, transcribeRest = true, manual = manual)
        buffer.take(at)
        bufferStart += at
        speechStart = (speechStart - at).coerceAtLeast(0)
        phraseStart = (phraseStart - at).coerceAtLeast(0)
        lastPhraseEnd = if (lastPhraseEnd > at) lastPhraseEnd - at else -1
        if (lastPhraseEnd < 0) lastPhraseLang = null
        // 区切った後ろは別の話し手の可能性があるので、カードの言語は決め直す（呼び出し側が分かっていれば上書きする）
        resetCardState()
        // 区切った後ろの文は、次のカードで書き起こし直す（区切る位置が文の途中のこともあるため）
        val next = openCard()
        if (lang != null) setCardLang(lang)
        moved.forEach { end -> phraseEnded(next, rel(end)) }
    }

    /**
     * カードを [at]（ためた音声の中の位置）で区切ると決める。文ごとに書き起こすモデルは、書き起こした文をつなぎ、残り（区切る位置が
     * 文の途中なら、その文の頭から）だけを書き起こす。[transcribeRest] が false なら残りは書き起こさない（後ろが無音と分かっているとき）。
     * ほかのモデルはカード全体を書き起こし直す。書き起こしがすべて届いたら確定を出す（[finishIfReady]）。
     */
    private fun close(card: Card, at: Int, reason: String, transcribeRest: Boolean, manual: Boolean = false, speakingEnd: Boolean = false) {
        val start = card.start
        val end = abs(at)
        val parts = mutableListOf<Phrase>()
        if (end - start >= MIN_PARTIAL_SAMPLES) {
            if (byPhrase) {
                card.phrases.removeAll { it.end > end }
                parts += card.phrases
                val from = parts.lastOrNull()?.end ?: start
                if (transcribeRest && end - from >= MIN_PARTIAL_SAMPLES) {
                    val rest = Phrase(from, end)
                    parts += rest
                    transcribe(card, rest, RecognitionPriority.FINAL, "確定の残り") { r ->
                        rest.recognition = r
                        finishIfReady(card)
                    }
                }
            } else {
                val whole = Phrase(start, end)
                parts += whole
                transcribe(card, whole, RecognitionPriority.FINAL, "確定") { r ->
                    whole.recognition = r
                    finishIfReady(card)
                }
            }
        }
        card.closing = Closing(start, end, reason, clock(), parts, manual, speakingEnd)
        if (open === card) open = null
        trace { "区切ると決めた（$reason）${at(start)}〜${at(end)}" }
        finishIfReady(card)
    }

    /** 区切ると決めたカードの書き起こしがすべて届いていれば、確定を出す。 */
    private fun finishIfReady(card: Card) {
        val closing = card.closing ?: return
        if (card.done || closing.parts.any { it.recognition == null }) return
        val r = TranscriptJoin.stitch(closing.parts.mapNotNull { it.recognition })
        val waitedMs = (clock() - closing.startedAt) / 1_000_000
        trace { "確定（${closing.reason}）${at(closing.start)}〜${at(closing.end)}: [${r.lang}] ${r.text} ／ 直前の途中 ${card.lastText}（区切ってから${waitedMs}ms）" }
        if (r.text.isNotEmpty()) {
            send(
                card,
                SpeechEvent.Final(
                    Utterance(
                        text = r.text,
                        lang = r.lang,
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
        pump()
    }

    // ---- 途中結果 ----

    /**
     * 途中結果を頼む（同じカードで1件ずつ）。文ごとに書き起こすモデルは、カードの言語が決まってから、まだ終わっていない文だけを
     * 書き起こし、書き起こした文につなぐ（言語が決まる前に書くと、会話に無い言語で書き起こされる）。ほかのモデルはカード全体を書き起こす。
     */
    private fun requestPartial(card: Card) {
        if (card.partialInFlight || card !== open) return
        val from = if (byPhrase) stitchedEnd(card) else 0
        if (byPhrase && cardLang == null) return
        if (buffer.size - from < MIN_PARTIAL_SAMPLES) return
        val fromAbs = abs(from)
        val phrase = Phrase(fromAbs, abs(buffer.size))
        val submittedAt = clock()
        card.partialInFlight = true
        transcribe(card, phrase, RecognitionPriority.PARTIAL, "途中") { r ->
            card.partialInFlight = false
            // 途中結果の間隔は、かかった時間（待ち時間を含む）の何倍か。音声の処理は止まらないので、CPU の取り分だけを決める
            val costSamples = ((clock() - submittedAt) / 1_000_000 * SAMPLE_RATE / 1000).toInt()
            partialInterval = maxOf(MIN_PARTIAL_INTERVAL, costSamples * if (byPhrase) PHRASE_PARTIAL_COST_RATIO else PARTIAL_COST_RATIO)
            if (card !== open) return@transcribe
            lastPartial = r
            lastPartialStart = fromAbs
            if (byPhrase) {
                card.tail = r
                card.tailFrom = fromAbs
                showStitched(card)
            } else {
                showPartial(card, r)
            }
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

    /** 途中結果を知らせる。同じ文字を続けて出さない（話し終わってから区切るまでの無音の間は同じ文字が続く）。 */
    private fun showPartial(card: Card, r: Recognition) {
        if (r.text.isEmpty() || r.text == card.lastText) return
        card.lastText = r.text
        send(card, SpeechEvent.Partial(r.text, r.lang))
    }

    /**
     * 長い発話を区切る位置の候補（文の切れ目）。直前の途中結果の切れ目を、ためた音声の中の位置に直す。
     * 文ごとに書き起こすモデルは、書き起こした文の終わり（VAD の文の区切り）も候補にする。
     */
    private fun cutBreaks(card: Card): List<SpeechBreak> {
        val offset = rel(lastPartialStart)
        val partialBreaks = lastPartial?.breaks.orEmpty().map { it.copy(at = it.at + offset) }
        return if (byPhrase) card.phrases.map { SpeechBreak(rel(it.end), sentence = true) } + partialBreaks else partialBreaks
    }

    // ---- 仕事と出来事 ----

    private fun job(make: (Long) -> RecognitionJob, handler: (RecognitionOutput) -> Unit) {
        val id = nextJob++
        handlers[id] = handler
        submit(make(id))
    }

    /**
     * [phrase] の音声を書き起こしてもらう。言語はカードの言語を渡す（識別を省く）。まだ無ければ識別してもらい、識別できなければ
     * 直前の文の言語として書き起こす。書き起こしの重いモデルは、話し始めの前に足した音声のうち、声の始まりより前を渡さない。
     */
    private fun transcribe(card: Card, phrase: Phrase, priority: RecognitionPriority, purpose: String, then: (Recognition) -> Unit) {
        val to = rel(phrase.end).coerceAtMost(buffer.size)
        var from = rel(phrase.from).coerceAtLeast(0)
        if (byPhrase && from == 0 && speechStart > 0) from = SpeechOnset.start(buffer.slice(0, speechStart), speechStart).coerceAtMost(to)
        val language = cardLang
        val fallback = lastPhraseLang ?: languages.primary
        val fromAbs = abs(from)
        job(
            { id ->
                RecognitionJob.Transcribe(id, card.id, priority, buffer.slice(from, to), phrase.end, purpose, languages, language, fallback)
            },
        ) { out ->
            val r = TranscriptCleanup.clean((out as? RecognitionOutput.Transcribed)?.recognition ?: Recognition("", null))
            trace { "書き起こし（$purpose）${at(fromAbs)}〜${at(phrase.end)} 言語の指定 $language: [${r.lang}] ${r.text}" }
            then(r)
        }
    }

    /** ためた音声の [from]〜[to] の言語（[LanguageEvidence] の組）を判定してもらう。短すぎればすぐ null を返す。 */
    private fun language(card: Card, from: Int, to: Int, purpose: String, then: (String?) -> Unit) {
        if (to - from < MIN_PARTIAL_SAMPLES) {
            then(null)
            return
        }
        val fromAbs = abs(from)
        val toAbs = abs(to)
        job({ id -> RecognitionJob.Language(id, card.id, languageLane, buffer.slice(from, to), toAbs, purpose, languages) }) { out ->
            val lang = (out as? RecognitionOutput.Identified)?.lang
            trace { "言語（$purpose）${at(fromAbs)}〜${at(toAbs)}: $lang" }
            then(lang)
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

        /** 話している最中に言語を見張る間隔（話した音声の長さ）。途中結果の間隔とは別に数える。 */
        private const val WATCH_INTERVAL_SAMPLES = SAMPLE_RATE / 2

        /** 途中結果の間隔を、かかった時間の何倍にするか（書き起こしの重いモデルは2倍、ほかは3倍）。 */
        private const val PHRASE_PARTIAL_COST_RATIO = 2
        private const val PARTIAL_COST_RATIO = 3

        /** 短すぎる音声は認識しても空になりやすいので、0.4秒たまってから認識する。 */
        private const val MIN_PARTIAL_SAMPLES = SAMPLE_RATE * 4 / 10

        /** 次の文の言語を判定するのに使う長さ。短いと言語の判定が揺れる。 */
        /** 次の文の言語を、文の全体で確かめる最短の長さ。 */
        private const val MIN_SWITCH_CHECK_SAMPLES = SAMPLE_RATE

        /** カードの言語を判定し直す窓の、一番広い長さと、広げる刻み。 */
        private const val CARD_LANG_MAX_SAMPLES = SAMPLE_RATE * 5
        private const val CARD_LANG_RETRY_SAMPLES = SAMPLE_RATE

        /** 話している最中の言語の見張りに使う、直近の音声の長さ。 */
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

        /** 言語の判定の目的（ログに出す）。言語の見張りはカードごとに1件ずつ頼む。 */
        private const val PURPOSE_CARD = "カードの頭"
        private const val PURPOSE_WATCH = "直近"
        private const val PURPOSE_NEXT_PHRASE = "次の文の頭"
        private const val PURPOSE_PHRASE = "文の言語"
        private const val PURPOSE_SEAM = "継ぎ目探し"
    }
}
