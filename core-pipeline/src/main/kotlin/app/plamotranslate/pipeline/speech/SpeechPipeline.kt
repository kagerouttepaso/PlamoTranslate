package app.plamotranslate.pipeline.speech

import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * 音声認識の流れ（[SpeechEngine] の実装）。止まってはいけない処理と重い処理を、別のスレッドに分ける
 * （docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md「認識を段に分ける」）。
 *
 * - 区切りのスレッド：命令（音声・区切る・止める）と認識の結果を届いた順に受け取り、区切りの状態遷移（[SpeechSegmenter]）に渡す。
 *   VAD は軽いので、重い書き起こしの間も音声は遅れずに区切られる。
 * - 認識の担当のスレッド（[RecognitionLane] ごとに1本）：待ち行列（[RecognitionQueue]）から次の仕事を取り、[recognizer] で処理して、
 *   結果を区切りのスレッドへ返す。書き起こしの担当と識別の担当が分かれているので、重い書き起こしの間も言語の見張りは止まらない。
 *
 * 状態は区切りのスレッドだけが持ち、担当のスレッドとは待ち行列（[lock] で守る）と受け取り口（[inbox]）でだけやり取りする。
 * [timing] は仕事ごとの待ち時間と処理時間を書く口（話した言葉は含めないので、いつも書く）。
 *
 * 止め終わりの約束：[flush] を呼んだら、閉じた後でも、認識の担当が失敗しても、[SpeechEvent.Flushed] が1回届く。
 * 呼んだ側は Flushed を待って聞き終えるので、届かないと止まらなくなるため。
 */
class SpeechPipeline(
    vad: VoiceActivity,
    private val recognizer: SpeechRecognizer,
    profile: SegmentationProfile = SegmentationProfile.CONVERSATION,
    byPhrase: Boolean = false,
    trace: (() -> String) -> Unit = {},
    private val timing: (String) -> Unit = {},
) : SpeechEngine {
    override var listener: (SpeechEvent) -> Unit = {}
    override var loadListener: (RecognitionLoad) -> Unit = {}

    private sealed interface Input {
        class Audio(val samples: FloatArray) : Input
        data object Cut : Input
        data object Flush : Input
        data object Reset : Input
        class Result(val job: RecognitionJob, val output: RecognitionOutput) : Input
        data object Close : Input
    }

    private val inbox = LinkedBlockingQueue<Input>()
    private val runner = RecognitionRunner(recognizer)

    /** [queue]・[running]・[submittedAt] を守る。担当のスレッドは仕事が無ければここで待つ。 */
    private val lock = Object()
    private val queue = RecognitionQueue()
    private val running = HashMap<RecognitionLane, RecognitionJob>()
    private val submittedAt = HashMap<Long, Long>()

    @Volatile private var stopping = false

    /** [closed] と、受け取り口へ入れる順番を守る（止めるより後に閉じたら、止める命令は必ず閉じる命令より前に入る）。 */
    private val gate = Object()
    @Volatile private var closed = false

    /** 止める命令を受け取り、まだ [SpeechEvent.Flushed] を出していない（区切りのスレッドだけが読み書きする）。 */
    private var flushPending = false

    /** 区切りのスレッドが受け取った音声のサンプル数（認識の遅れに使う）。 */
    private var received = 0L
    private var lastLoad = RecognitionLoad()

    private val segmenter = SpeechSegmenter(
        vad,
        identifies = recognizer.identifies,
        profile = profile,
        byPhrase = byPhrase,
        emit = { event ->
            if (event == SpeechEvent.Flushed) flushPending = false
            listener(event)
        },
        submit = ::enqueue,
        trace = trace,
    )

    override var languages: SpeechLanguages
        get() = segmenter.languages
        set(value) {
            segmenter.languages = value
        }

    private val control = thread(name = "PlamoAsrSegmenter") { controlLoop() }

    private val lanes = (if (recognizer.identifies) RecognitionLane.entries else listOf(RecognitionLane.TRANSCRIBE))
        .map { lane -> thread(name = "PlamoAsr-${lane.name.lowercase()}") { work(lane) } }

    override fun accept(samples: FloatArray) {
        if (!closed) inbox.put(Input.Audio(samples))
    }

    override fun cut() {
        if (!closed) inbox.put(Input.Cut)
    }

    override fun flush() = synchronized(gate) {
        // 閉じた後なら、止める仕事はもう無い。待っている呼び手のために、その場で止め終わりを返す
        if (closed) listener(SpeechEvent.Flushed) else inbox.put(Input.Flush)
    }

    override fun reset() {
        if (!closed) inbox.put(Input.Reset)
    }

    /**
     * 区切りのスレッドを止め、待ち行列の残りを捨て、担当のスレッドが今の仕事を終えてから、モデルを解放する（呼んだスレッドは待たない）。
     * 止めている途中なら、確定を待たずに止め終わりを返す。
     */
    override fun close() {
        synchronized(gate) {
            if (closed) return
            closed = true
            inbox.put(Input.Close)
        }
        thread(name = "PlamoAsrClose") {
            control.join()
            stopping = true
            synchronized(lock) {
                queue.clear()
                lock.notifyAll()
            }
            lanes.forEach { it.join() }
            recognizer.close()
        }
    }

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
                        synchronized(lock) { queue.clear() }
                        segmenter.reset()
                        // 止めている途中で始め直したら、止める仕事は捨てたので、待っている呼び手に止め終わりを返す
                        if (flushPending) {
                            flushPending = false
                            listener(SpeechEvent.Flushed)
                        }
                        received = 0
                    }
                    is Input.Result -> segmenter.onResult(input.job.id, input.output)
                    Input.Close -> {
                        runCatching { segmenter.close() }
                        if (flushPending) listener(SpeechEvent.Flushed)
                        return
                    }
                }
            } catch (e: Throwable) {
                timing("区切りで失敗: $e")
            }
            // 閉じたカードの途中結果と言語の判定は、もう要らない
            synchronized(lock) { queue.dropStale(segmenter.openCardId) }
            publishLoad()
        }
    }

    private fun enqueue(job: RecognitionJob) = synchronized(lock) {
        queue.submit(job)
        submittedAt[job.id] = System.nanoTime()
        lock.notifyAll()
    }

    private fun work(lane: RecognitionLane) {
        while (true) {
            val job = synchronized(lock) {
                var next = queue.take(lane)
                while (next == null && !stopping) {
                    lock.wait()
                    next = queue.take(lane)
                }
                next?.also { running[lane] = it }
            } ?: return
            val began = System.nanoTime()
            val output = try {
                runner.run(job)
            } catch (e: Throwable) {
                // ネイティブの Error でも担当を止めない（結果が戻らないと、カードも止め終わりも出ない）
                timing("認識に失敗（${job.purpose}）: $e")
                RecognitionRunner.empty(job)
            }
            val ended = System.nanoTime()
            val waited = synchronized(lock) {
                running.remove(lane)
                submittedAt.remove(job.id)
            }
            timing(
                "認識 %s（%s）音声%.1f秒 待ち%dms 処理%dms".format(
                    job.purpose, lane.name.lowercase(), job.samples.size / SpeechEngine.SAMPLE_RATE.toFloat(),
                    waited?.let { (began - it) / 1_000_000 } ?: -1, (ended - began) / 1_000_000,
                ),
            )
            inbox.put(Input.Result(job, output))
        }
    }

    /** 認識の混み具合が変わったら知らせる（0.1秒刻み）。 */
    private fun publishLoad() {
        val (urgent, oldest) = synchronized(lock) {
            val urgentRunning = running.values.filter { it.priority <= RecognitionPriority.PHRASE }
            (queue.hasUrgent() || urgentRunning.isNotEmpty()) to
                listOfNotNull(queue.oldestTranscribeEnd(), urgentRunning.minOfOrNull { it.endAt }).minOrNull()
        }
        val behind = oldest?.let { ((received - it).coerceAtLeast(0) * 10 / SpeechEngine.SAMPLE_RATE) / 10f } ?: 0f
        val load = RecognitionLoad(behind, urgent)
        if (load != lastLoad) {
            lastLoad = load
            loadListener(load)
        }
    }
}
