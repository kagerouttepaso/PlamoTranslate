package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.compute.StopMode
import app.plamotranslate.llm.Language

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音声認識の流れ（[SpeechEngine] の実装）。止まってはいけない処理と重い処理を、別のスレッドに分ける
 * （docs/設計/会話翻訳.md「認識の段と CPU の割り当て」）。
 *
 * - セグメンターのスレッド（`PlamoAsrSegmenter`）：命令（音声・区切る・止める）と認識の結果を届いた順に受け取り、
 *   セグメンター（[SpeechSegmenter]）に渡す。
 *   VAD は軽いので、重い書き起こしの間も音声は遅れずに区切られる。
 * - 認識のワーカーのスレッド（[RecognitionLane] ごとに1本）：待ち行列（[RecognitionQueue]）から次のジョブを取り、[recognizer] で処理して、
 *   結果をセグメンターのスレッドへ返す。書き起こしのワーカーと識別のワーカーが分かれているので、重い書き起こしの間も言語の監視は止まらない。
 *
 * 確定した発話の音声の取り置きと書き起こし直し（[retranscribe]）は [UtteranceRetention] が受け持ち、セグメンターのスレッドで動く。
 * 書き起こし直しのジョブ（[RecognitionJob.Retranscribe]）の結果は、セグメンターでなく取り置きに渡す。
 *
 * 状態はセグメンターのスレッドだけが持ち、ワーカーのスレッドとは命令と終了通知（[inbox]）でだけやり取りする。
 * [timing] はジョブごとの待ち時間と処理時間を書く口（話した言葉は含めないので、いつも書く）。
 *
 * 止め終わりの約束：[flush] を呼んだら、閉じた後でも、認識のワーカーが失敗しても、[SpeechEvent.Flushed] が1回届く。
 * 呼んだ側は Flushed を待って聞き終えるので、届かないと止まらなくなるため。
 */
class SpeechPipeline(
    vad: VoiceActivity,
    private val recognizer: SpeechRecognizer,
    profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    heavy: Boolean = false,
    trace: (() -> String) -> Unit = {},
    private val timing: (String) -> Unit = {},
    /** 書き起こし直しのために保持する確定した発話の数（[UtteranceRetention]）。0 なら保持しない。 */
    retainUtterances: Int = 0,
    private val identifier: LanguageIdentifier? = null,
) : SpeechEngine {
    private val eventStream = MutableSharedFlow<SpeechEvent>(extraBufferCapacity = Int.MAX_VALUE)
    override val events = eventStream.asSharedFlow()
    private val currentLoad = MutableStateFlow(RecognitionLoad())
    override val load = currentLoad.asStateFlow()

    private fun emit(event: SpeechEvent) { check(eventStream.tryEmit(event)) }

    private sealed interface Input {
        class Audio(val samples: FloatArray) : Input
        data object Cut : Input
        data object Flush : Input
        data object Reset : Input
        class Retranscribe(val utterance: Long, val language: Language) : Input
        class Result(val lane: RecognitionLane, val end: RecognitionEnd) : Input
        data object Close : Input
    }

    private val inbox = LinkedBlockingQueue<Input>()
    private val runner = RecognitionRunner(recognizer, identifier)

    private class Flight(val request: RecognitionRequest, val started: Long, var stoppedBy: RecognitionJob? = null, var stopTime: Long = 0) {
        val cancelled get() = stoppedBy != null
    }
    private val queue = RecognitionQueue()
    private val running = mutableMapOf<RecognitionLane, Flight>()
    private val submittedAt = mutableMapOf<Long, Long>()
    private var generation = 0L

    /** [closed] と、受け取り口へ入れる順番を守る（止めるより後に閉じたら、止める命令は必ず閉じる命令より前に入る）。 */
    private val gate = Object()
    @Volatile private var closed = false
    private val closedSignal = CompletableDeferred<Unit>()

    /** 止める命令を受け取り、まだ [SpeechEvent.Flushed] を出していない（セグメンターのスレッドだけが読み書きする）。 */
    private var flushPending = false

    /** セグメンターのスレッドが受け取った音声のサンプル数（認識の遅れに使う）。 */
    private var received = 0L

    /** ジョブの番号の採番。区切りと保持が同じものを使い、番号が重ならないようにする。 */
    private val jobIds = JobIds()

    private val retention = UtteranceRetention(retainUtterances, emit = { emit(it) }, submit = ::enqueue, trace = trace, jobIds = jobIds)

    private val segmenter = SpeechSegmenter(
        vad,
        identifies = identifier != null,
        judgesFinalTranscript = recognizer.judgesFinalTranscript,
        profile = profile,
        heavy = heavy,
        emit = { event ->
            if (event == SpeechEvent.Flushed) flushPending = false
            emit(event)
            // 確定を出した後に取り置く（手放したイベント（Released）は、その確定の後に届く）
            if (event is SpeechEvent.CardFinished) event.utterance?.let(retention::finalEmitted)
        },
        submit = ::enqueue,
        trace = trace,
        retain = retention::retain,
        jobIds = jobIds,
    )

    override var languages: SpeechLanguages
        get() = segmenter.languages
        set(value) {
            segmenter.languages = value
        }

    private val workers = (if (identifier != null) RecognitionLane.entries else listOf(RecognitionLane.TRANSCRIBE))
        .associateWith { lane ->
            RecognitionWorker(lane, runner::run).also { worker ->
                CoroutineScope(Dispatchers.Unconfined).launch {
                    worker.results.collect { inbox.put(Input.Result(lane, it)) }
                }
            }
        }
    private val control = thread(name = "PlamoAsrSegmenter") { controlLoop() }

    override fun accept(samples: FloatArray) {
        if (!closed) inbox.put(Input.Audio(samples))
    }

    override fun cut() {
        if (!closed) inbox.put(Input.Cut)
    }

    override fun flush() = synchronized(gate) {
        // 閉じた後なら、止める処理はもう無い。待っている呼び手のために、その場で止め終わりを返す
        if (closed) emit(SpeechEvent.Flushed) else inbox.put(Input.Flush)
    }

    override fun reset() {
        if (!closed) inbox.put(Input.Reset)
    }

    override fun retranscribe(utterance: Long, language: Language) {
        if (!closed) inbox.put(Input.Retranscribe(utterance, language))
    }

    /**
     * セグメンターのスレッドを止め、待ち行列の残りを捨て、ワーカーのスレッドが今のジョブを終えてから、モデルを解放する（呼んだスレッドは待たない）。
     * 止めている途中なら、確定を待たずに止め終わりを返す。
     */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            inbox.put(Input.Close)
        }
        thread(name = "PlamoAsrClose") {
            try {
                control.join()
                workers.values.forEach { it.close() }
                try { recognizer.close() } finally { identifier?.close() }
            } finally {
                closedSignal.complete(Unit)
            }
        }
    }

    override suspend fun awaitClosed() = closedSignal.await()

    private fun controlLoop() {
        while (true) {
            val input = inbox.take()
            try {
                when (input) {
                    is Input.Audio -> {
                        received += input.samples.size
                        segmenter.accept(input.samples)
                    }
                    Input.Cut -> segmenter.cut()
                    Input.Flush -> {
                        flushPending = true
                        segmenter.flush()
                    }
                    Input.Reset -> {
                        generation++
                        queue.clear()
                        submittedAt.clear()
                        running.forEach { (lane, flight) -> workers.getValue(lane).cancel(flight.request.id) }
                        segmenter.reset()
                        // 待っていた書き起こし直しは待ち行列ごと捨てたので、取り置いた音声から出し直す
                        retention.reset()
                        // 止めている途中で始め直したら、止める処理は捨てたので、待っている呼び手に止め終わりを返す
                        if (flushPending) {
                            flushPending = false
                            emit(SpeechEvent.Flushed)
                        }
                        received = 0
                    }
                    is Input.Retranscribe -> retention.retranscribe(input.utterance, input.language)
                    is Input.Result -> finish(input)
                    Input.Close -> {
                        runCatching { segmenter.close() }
                        if (flushPending) emit(SpeechEvent.Flushed)
                        return
                    }
                }
            } catch (e: Throwable) {
                timing("区切りで失敗: $e")
            }
            dropStale()
            dispatch()
            publishLoad()
        }
    }

    /**
     * 閉じたカードの途中結果と言語の判定は、もう要らないので捨てる。捨てたジョブにも空の結果を渡す（セグメンターは結果を受けてから
     * 次を決めるので、知らせないとそのジョブを待ち続ける）。渡した結果でセグメンターがジョブを足し、また捨てられることがあるので、
     * 捨てるものが無くなるまで繰り返す。
     */
    private fun dropStale() {
        while (true) {
            val stale = queue.dropStale(segmenter.openCardId).onEach { submittedAt.remove(it.id) }
            if (stale.isEmpty()) return
            stale.forEach { job ->
                try {
                    segmenter.onResult(job.id, RecognitionRunner.empty(job))
                } catch (e: Throwable) {
                    timing("区切りで失敗: $e")
                }
            }
        }
    }

    private fun enqueue(job: RecognitionJob) {
        // 同じワーカーで待つ依頼は、まだプロセスの貸出待ちに現れない。
        val flight = running[job.lane]
        if (flight != null) {
            val active = flight.request.job.priority.work
            if (!flight.cancelled && active.stop == StopMode.Cancel && job.priority.work.priority < active.priority) {
                flight.stoppedBy = job
                flight.stopTime = System.nanoTime()
                workers.getValue(job.lane).cancel(flight.request.id)
            }
        }
        queue.submit(job)
        submittedAt[job.id] = System.nanoTime()
    }

    private fun dispatch() {
        for ((lane, worker) in workers) {
            if (lane in running) continue
            val job = queue.take(lane) ?: continue
            val request = RecognitionRequest(generation, job)
            running[lane] = Flight(request, System.nanoTime())
            worker.submit(request)
        }
    }

    private fun finish(result: Input.Result) {
        val flight = running[result.lane]?.takeIf { it.request.id == result.end.id } ?: return
        running.remove(result.lane)
        if (result.end.id.generation != generation) return
        val job = flight.request.job
        val waited = submittedAt.remove(job.id)
        timing("認識 %s（%s）音声%.1f秒 待ち%dms 処理%dms".format(job.purpose, result.lane.name.lowercase(),
            job.samples.size / SpeechEngine.SAMPLE_RATE.toFloat(), waited?.let { (flight.started - it) / 1_000_000 } ?: -1,
            (System.nanoTime() - flight.started) / 1_000_000))
        flight.stoppedBy?.let { higher ->
            timing("認識 横取り ${higher.priority}#${higher.id} → ${job.priority}#${job.id} 返却まで=${(System.nanoTime() - flight.stopTime) / 1_000_000}ms")
        }
        val output = when (val end = result.end) {
            is RecognitionEnd.Success -> if (flight.cancelled) RecognitionOutput.Cancelled else end.output
            is RecognitionEnd.Cancelled -> RecognitionOutput.Cancelled
            is RecognitionEnd.Failed -> {
                timing("認識に失敗（${job.purpose}）: ${end.cause}")
                if (flight.cancelled) RecognitionOutput.Cancelled else RecognitionRunner.empty(job)
            }
        }
        if (job is RecognitionJob.Retranscribe) retention.onResult(job, output) else segmenter.onResult(job.id, output)
    }

    private fun publishLoad() {
        val urgentRunning = running.values.filter { it.request.generation == generation }.map { it.request.job }.filter { it.priority.urgent }
        val urgent = queue.hasUrgent() || urgentRunning.isNotEmpty()
        val oldest = listOfNotNull(queue.oldestTranscribeEnd(), urgentRunning.minOfOrNull { it.endAt }).minOrNull()
        val behind = oldest?.let { ((received - it).coerceAtLeast(0) * 10 / SpeechEngine.SAMPLE_RATE) / 10f } ?: 0f
        currentLoad.value = RecognitionLoad(behind, urgent)
    }
}
