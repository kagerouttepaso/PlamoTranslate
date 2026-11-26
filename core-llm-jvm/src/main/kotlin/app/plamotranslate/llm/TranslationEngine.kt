package app.plamotranslate.llm

import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.TranslationTurn
import app.plamotranslate.pipeline.diagnostics.InferenceTarget
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.pipeline.translation.SingleRequest
import app.plamotranslate.log.Logs
import java.io.File
import java.util.concurrent.Executors

class TranslationException(message: String) : Exception(message)

/**
 * CPUで動かすときの細かい設定（2026-11-08、15T Pro（MediaTek）でアプリ内の生成がCLIの約200分の1に落ちたため、
 * 原因の候補を設定の画面で切り替えて試せるようにした。docs/設計/翻訳コア.md「性能の設定」）。既定は今までの動き。
 */
data class CpuOptions(
    /**
     * 0なら許された CPU の性能の段から試走で選ぶ。
     */
    val threads: Int = 0,
    /** 性能ヒント（ADPF）で周波数・配置を上げてもらう。 */
    val boost: Boolean = true,
    /** 処理を待つ間にスピン待機する（`poll`。false なら眠って待つ）。 */
    val spinWait: Boolean = true,
    /** スレッドプールを常駐させる（false ならllama.cppが推論のたびに用意する）。 */
    val threadPool: Boolean = true,
    /** モデルをメモリマップ（mmap）で読む（false ならメモリへ全部読み込む）。 */
    val mmap: Boolean = true,
    /** CPUバックエンドの版（libggml-cpu-*.so）。空なら端末に合う一番新しい版。アプリを起動し直すと効く。 */
    val variant: String = "",
)

/**
 * PLaMo 3 Translateを1つ保持し、中核の翻訳のエンジンのインターフェース（[JobEngine]）として訳す（docs/設計/翻訳コア.md「翻訳の契約」）。
 * 機能はこのクラスを直接呼ばず、中核の翻訳のストリーム（`TranslationStream`）を通す。
 * llama.cppのコンテキストは同時に使えないため、すべての呼び出しを1本のスレッドへ寄せる。
 */
class TranslationEngine private constructor(
    private val handle: Long,
    private val dispatcher: ExecutorCoroutineDispatcher,
    /** GPU（OpenCL）で動いているか。false ならCPUだけ。 */
    val usesGpu: Boolean,
    private var selectedThreads: Int,
    private var boost: Boolean,
) : JobEngine {
    private val modelLock = Mutex()
    private val runtime get() = LlamaRuntime.process

    /**
     * 同時に訳す文の数（1〜[MAX_PARALLEL]。1なら1件ずつ）。設定の画面の「同時に訳す数」をアプリが入れる。読み直さずに変えられる。
     */
    @Volatile
    override var parallel: Int = DEFAULT_PARALLEL
        set(value) {
            field = value.coerceIn(1, MAX_PARALLEL)
        }

    /**
     * [source] から依頼を受け取り、最大 [slots] 本（[MAX_PARALLEL] まで）を同時に訳す（2026-11-12）。
     * 1回の計算で全部のシーケンスを1トークンずつ進めるので、1件ずつ訳すより合計が速い。訳し終わったシーケンスには、ほかのシーケンスを
     * 待たずにすぐ次の依頼を入れる（docs/設計/翻訳コア.md「複数の文を同時に訳す」）。[source] に依頼が無くなり、
     * 全部のシーケンスが訳し終えたら戻る。戻り値は全部の依頼を合わせた生成トークン数。1文だけ訳すときも、シーケンス1本でこれを使う
     * （2026-11-12に1文用の入口と生成の処理を取り除いた。翻訳の契約を1本にするため）。
     */
    override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int =
        modelLock.withLock {
            checkOpen()
            withContext(dispatcher) {
                var tokens = 0
                val parallel = slots.coerceIn(1, MAX_PARALLEL)
                ProcessCompute.borrow(usesGpu, selectedThreads, ComputeWork.Translation) { count, waiting ->
                    runtime.native { LlamaNative.nativeConfigure(handle, boost, count) }
                    tokens += translateBatch(parallel, source, maxTokens, TranslationTurn(parallel, waiting))
                }
                tokens
            }
        }

    private suspend fun translateBatch(slots: Int, source: JobSource, maxTokens: Int, turn: TranslationTurn): Int {
        val scope = currentCoroutineContext()
        val prompts = HashMap<Int, String>()
        val callback = object : LlamaNative.JobCallback {
            override fun nextJob(): Int {
                if (!scope.isActive) return -1
                val (id, request) = turn.next(source::next) ?: return -1
                prompts[id] = Pfml3PromptBuilder.build(request)
                return id
            }

            override fun prompt(id: Int): String = prompts.remove(id).orEmpty()

            override fun onToken(id: Int, utf8: ByteArray): Int = when {
                !scope.isActive -> 2
                source.onText(id, String(utf8, Charsets.UTF_8)) -> 1
                else -> 0
            }

            override fun onFinished(id: Int) = source.onFinished(id)
            override fun onTooLong(id: Int) = source.onTooLong(id)
        }
        val result = runtime.native { LlamaNative.nativeTranslateJobs(handle, slots, maxTokens, callback) }
        scope.ensureActive()
        if (result < 0) throw TranslationException(errorMessage(result))
        return result
    }

    /** モデルのロックを取って読み書きする。閉じた後の翻訳を止めるためのフラグ。 */
    private var closed = false

    /**
     * モデルを解放する。推論のスレッドで順番に行うので、走っている翻訳が終わってから解放される。
     * 解放した後に届いた翻訳の依頼は [TranslationException] で失敗する（翻訳モデルの切り替え、2026-11-06）。
     */
    suspend fun close() = withContext(NonCancellable) {
        modelLock.withLock {
            if (!closed) {
                closed = true
                try {
                    withContext(dispatcher) { ProcessCompute.borrow(usesGpu, 1) { _, _ -> runtime.native { LlamaNative.nativeFree(handle) } } }
                } finally {
                    dispatcher.close()
                }
            }
        }
    }

    /** デバッグ計測の設定。次の貸出時に反映する。 */
    suspend fun configure(boost: Boolean, threads: Int = 0) =
        modelLock.withLock {
            checkOpen()
            this.boost = boost
            if (threads > 0) selectedThreads = threads
        }

    private suspend fun trial(count: Int): Long {
        val request = TranslationRequest("明日の朝は駅の北口で待ち合わせましょう。", Language.JA, Language.EN)
        runtime.native { LlamaNative.nativeConfigure(handle, boost, count) }
        val start = System.nanoTime()
        val tokens = translateBatch(1, SingleRequest(request), 8, TranslationTurn(1) { false })
        check(tokens > 0)
        return (System.nanoTime() - start) / tokens
    }

    private fun checkOpen() {
        if (closed) throw TranslationException("翻訳モデルを切り替えたため、この翻訳は中止しました")
    }

    companion object {
        /**
         * コンテキスト長。2026-11-12に2048から4096にした。複数の文を同時に訳すとき、全部の文と訳がこの中に入る必要がある
         * （1文だけを訳すときは全部を1文に使える）。
         */
        const val DEFAULT_CONTEXT = 4096
        const val DEFAULT_MAX_TOKENS = 1024

        /** 同時に訳せる文の数の上限（読み込むときにシーケンスの数として確保する）。 */
        const val MAX_PARALLEL = 8

        /**
         * 同時に訳す文の数の既定。2026-11-12にREDMAGIC 11 Proで測り、GPUは4本で生成が約1.7倍（2Bは8本にしても同じ）、
         * CPUは4本で約2.5倍だった（docs/設計/翻訳コア.md「複数の文を同時に訳す」）。端末ごとの値は設定の画面の自動調整で探す。
         */
        const val DEFAULT_PARALLEL = 4

        /**
         * 全層をGPU（OpenCL、Adreno 840）に載せる。2026-11-05の実測で、プロンプト処理はCPUの約5倍（約1170トークン/秒）、
         * 生成はCPUと同程度（約80トークン/秒）だが、REDMAGICのCPUクロック上限の影響を受けない（docs/判断/2026-11-05_翻訳は全層をGPUのOpenCLで動かす.md）。
         */
        const val DEFAULT_GPU_LAYERS = 99

        /**
         * モデルを読み込む。数秒かかるので起動時に1回だけ呼ぶ。
         *
         * [nativeLibDir] はバックエンドのライブラリ（libggml-opencl.so、libggml-cpu-*.so）の置き場
         * （`applicationInfo.nativeLibraryDir`）。[target] が GPU を選んだ場合、GPU（OpenCL）が使えれば全層を載せる。
         * GPUが無い・GPUでの読み込みに失敗したときは、CPUだけで読み込み直す（2026-11-06、APKを配布するため）。
         */
        suspend fun load(
            model: File,
            nativeLibDir: String,
            target: InferenceTarget,
            nCtx: Int = DEFAULT_CONTEXT,
            cpu: CpuOptions = CpuOptions(),
            cacheDir: File? = null,
        ): TranslationEngine {
            if (!model.canRead()) throw TranslationException("モデルが読めません: ${model.path}")
            val dispatcher = Executors.newSingleThreadExecutor { task -> Thread(task, "PlamoInference") }.asCoroutineDispatcher()
            val runtime = LlamaRuntime.process
            var loaded: TranslationEngine? = null
            try {
                return withContext(dispatcher) {
                    var poolCapacity = 1
                    val requested = ProcessCompute.availableTopology().candidates(cpu.threads).last()
                    var gpu = runtime.initialize(nativeLibDir, cpu.variant, target)
                    suspend fun loadHandle(gpu: Boolean) = ProcessCompute.borrow(gpu, requested) { n, _ ->
                        poolCapacity = n
                        runtime.native { LlamaNative.nativeLoad(model.path, nCtx, n, if (gpu) DEFAULT_GPU_LAYERS else 0,
                            if (cpu.spinWait) DEFAULT_POLL else 0, cpu.threadPool, cpu.mmap, cpu.boost, MAX_PARALLEL) }
                    }
                    var handle = loadHandle(gpu)
                    if (handle == 0L && gpu) {
                        gpu = false
                        handle = loadHandle(false)
                    }
                    if (handle == 0L) throw TranslationException("モデルの読み込みに失敗しました: ${model.name}")
                    val engine = TranslationEngine(handle, dispatcher, gpu, poolCapacity, cpu.boost)
                    loaded = engine
                    val topology = ProcessCompute.availableTopology()
                    val version = listOf(model.absolutePath, model.length(), model.lastModified(), gpu, cpu, nCtx).joinToString("|")
                    engine.selectedThreads = ProcessCompute.selectThreads(
                        ThreadBenchmark(ThreadBenchmark.Purpose.Translation, "PLaMo", version, cacheDir, engine::trial), cpu.threads, gpu, poolCapacity)
                    Logs.i("翻訳のスレッド数=${engine.selectedThreads} CPU=${topology.key}")
                    engine
                }
            } catch (failure: Throwable) {
                withContext(NonCancellable) { loaded?.close() ?: dispatcher.close() }
                throw failure
            }
        }

        /** llama.cppの既定。処理を待つ間、しばらくスピン待機してから眠る。 */
        private const val DEFAULT_POLL = 50

        /** 端末に入っているCPUバックエンドの版（ファイル名）。 */
        fun cpuVariants(nativeLibDir: String): List<String> =
            File(nativeLibDir).list()?.filter { it.startsWith("libggml-cpu") }?.sorted().orEmpty()

        /** 読み込んだCPUバックエンドの版。まだ読み込んでいない、または版名を返さないバックエンドでは空。 */
        fun cpuBackend(): String = LlamaRuntime.process.native { LlamaNative.nativeCpuBackend() }

        private fun errorMessage(code: Int) = when (code) {
            -1 -> "文をトークンに分けられませんでした"
            -2 -> "文が長すぎます（${DEFAULT_CONTEXT}トークンまで）"
            -3 -> "推論に失敗しました"
            -4 -> "翻訳の受け渡しに失敗しました"
            else -> "翻訳に失敗しました（$code）"
        }
    }
}
