package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language

/**
 * 確定した発話の音声の取り置きと、書き起こし直し（docs/設計/会話翻訳.md「カードの言語の取り違えを直す」）。
 * 利用者が確定したカードの言語の取り違えに気づいて直すのは直近の数枚なので、直近 [capacity] 枚の音声だけをメモリに持つ
 * （ファイルには書かない）。区切り（[SpeechSegmenter]）はカードを閉じるたびに音声を渡し（[retain]）、確定が出たら
 * 取り置きに入れる（[finalEmitted]）。音声を手放したら [SpeechEvent.Released] を出す。
 *
 * カードの区切りとは寿命が違う：区切りの [SpeechSegmenter.reset]（聞き直し）では捨てず、待っている書き起こし直しも出し直す
 * （[reset]。認識の待ち行列は始め直しで捨てられる）。状態を持つので、すべての呼び出しはセグメンターと同じ1本のスレッドから行う
 * （[SpeechPipeline]）。[trace] は経過を書く口（話した言葉を含む）。
 */
class UtteranceRetention(
    /** 保持する発話の数。0 なら保持しない（書き起こし直しを使わない、テキスト翻訳の音声入力など）。 */
    private val capacity: Int = KEEP_UTTERANCES,
    private val emit: (SpeechEvent) -> Unit,
    private val submit: (RecognitionJob) -> Unit,
    private val trace: (() -> String) -> Unit = {},
    private val clock: () -> Long = System::nanoTime,
    /** ジョブの番号の採番（区切りと同じものを渡す）。 */
    private val jobIds: JobIds = JobIds(),
) {
    private class Kept(val id: Long, val audio: FloatArray, val startMs: Long, val durationMs: Long)

    /** 書き起こし直しを待っている発話。[jobId] は出している書き起こし直しのジョブ。 */
    private class Pending(val language: Language, val startedAt: Long, var jobId: Long)

    /** カードを閉じたときに渡された音声（確定が出るまで）。 */
    private val closing = HashMap<Long, FloatArray>()

    /** 取り置いた音声（古い順）。 */
    private val kept = ArrayDeque<Kept>()

    private val pending = HashMap<Long, Pending>()

    /** 閉じたカード [id] の音声。確定（[finalEmitted]）が出たら取り置く。 */
    fun retain(id: Long, audio: FloatArray) {
        if (capacity == 0) return
        closing[id] = audio
    }

    /** 確定 [utterance] を出した。音声を取り置き、[capacity] 枚を超えたら一番古いものを手放して知らせる。 */
    fun finalEmitted(utterance: Utterance) {
        val audio = closing.remove(utterance.id) ?: return
        kept.addLast(Kept(utterance.id, audio, utterance.startMs, utterance.durationMs))
        while (kept.size > capacity) {
            val released = kept.removeFirst()
            emit(SpeechEvent.Released(released.id))
        }
    }

    /**
     * 発話 [id] を [language] として書き起こし直す。音声を持っていなければ、すぐ [RetranscribeFailure.RELEASED] を返す。
     * 同じ発話を待っている間に重ねて頼まれたら、1つの結果にまとめる（最初の言語のまま）。
     */
    fun retranscribe(id: Long, language: Language) {
        if (id in pending) return
        val audio = kept.firstOrNull { it.id == id }?.audio
        if (audio == null) {
            emit(SpeechEvent.RetranscribeFailed(id, RetranscribeFailure.RELEASED))
            return
        }
        val p = Pending(language, clock(), jobId = 0)
        pending[id] = p
        submitJob(id, p, audio)
    }

    /** 書き起こし直しのジョブの結果。もう待っていないジョブ（始め直しで出し直した前のもの）の結果は使わない。 */
    fun onResult(job: RecognitionJob.Retranscribe, output: RecognitionOutput) {
        val p = pending[job.utterance]?.takeIf { it.jobId == job.id } ?: return
        pending.remove(job.utterance)
        val r = TranscriptCleanup.clean((output as? RecognitionOutput.Transcribed)?.recognition ?: Recognition("", null))
        trace { "書き起こし直し 発話${job.utterance} 言語の指定 ${p.language}: [${r.lang}] ${r.text}" }
        val k = kept.firstOrNull { it.id == job.utterance }
        when {
            r.text.isEmpty() -> emit(SpeechEvent.RetranscribeFailed(job.utterance, RetranscribeFailure.NOTHING_HEARD))
            // 指定した言語で書き起こせなかった結果に、指定した言語を付けない（原文と言語が食い違うカードを作らない）
            r.source != LanguageSource.SPECIFIED -> emit(SpeechEvent.RetranscribeFailed(job.utterance, RetranscribeFailure.UNSUPPORTED))
            // 待っている間に手放した（新しい確定が続いた）。結果はできているので返す
            else -> emit(
                SpeechEvent.Retranscribed(
                    job.utterance,
                    Utterance(job.utterance, r.text, r.lang, k?.startMs ?: 0, k?.durationMs ?: 0, (clock() - p.startedAt) / 1_000_000),
                ),
            )
        }
    }

    /** 認識の待ち行列を捨てて始め直した。待っている書き起こし直しを出し直す（音声は取り置いている）。 */
    fun reset() {
        for ((id, p) in pending.entries.toList()) {
            val audio = kept.firstOrNull { it.id == id }?.audio
            if (audio == null) {
                pending.remove(id)
                emit(SpeechEvent.RetranscribeFailed(id, RetranscribeFailure.RELEASED))
            } else {
                submitJob(id, p, audio)
            }
        }
        closing.clear()
    }

    private fun submitJob(id: Long, p: Pending, audio: FloatArray) {
        p.jobId = jobIds.next()
        submit(RecognitionJob.Retranscribe(p.jobId, id, audio, PURPOSE, p.language))
    }

    companion object {
        /**
         * 音声を取り置く確定した発話の数。言語の取り違えに気づいて直すのは直近の数枚なので、多めに10枚とする
         * （1秒あたり64KB。カードは長い発話の区切りで最長約14秒なので、最大約9MB）。
         */
        const val KEEP_UTTERANCES = 10

        private const val PURPOSE = "書き起こし直し"
    }
}
