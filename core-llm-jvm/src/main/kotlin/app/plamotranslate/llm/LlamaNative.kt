package app.plamotranslate.llm

import app.plamotranslate.log.LogLevel
import app.plamotranslate.log.Logs

/** llama.cppのJNI入口。登録と実行の排他は [LlamaRuntime] が管理する。 */
internal object LlamaNative {
    init {
        System.loadLibrary("plamo-llm")
    }

    /**
     * ネイティブのログのコールバック（`plamo_llm.cpp` の emit_log が、JVM につながっているスレッドから呼ぶ）。ロガー [Logs] へ渡す。
     * [priority] は Android のログの重さ（3 デバッグ、4 情報、5 警告、6 誤り）、[utf8] は1行の文。
     */
    @JvmStatic
    fun onNativeLog(priority: Int, utf8: ByteArray) {
        val level = when {
            priority >= 6 -> LogLevel.ERROR
            priority == 5 -> LogLevel.WARN
            priority == 4 -> LogLevel.INFO
            else -> LogLevel.DEBUG
        }
        Logs.write(level, utf8.toString(Charsets.UTF_8).trimEnd(), null)
    }

    external fun nativeInitCpu(nativeLibDir: String, cpuVariant: String)
    external fun nativeRegisterGpu(nativeLibDir: String): Boolean

    /** 読み込んだCPUバックエンドのファイル名（libggml-cpu-*.so）。GPUの端末で自動選択したときは空。 */
    external fun nativeCpuBackend(): String

    /** Windows 専用。物理コアごとの EfficiencyClass。取得できなければ null。 */
    external fun nativeWindowsCoreClasses(): IntArray?

    /** 失敗したら 0 を返す。 */
    external fun nativeLoad(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int,
        poll: Int, useThreadPool: Boolean, useMmap: Boolean, boost: Boolean, nSeqMax: Int,
    ): Long
    external fun nativeFree(handle: Long)

    external fun nativeLoadAudio(model: String, projector: String, gpu: Boolean, encoderThreads: Int, batchThreads: Int, generationThreads: Int): Long
    external fun nativeTranscribe(handle: Long, samples: FloatArray, language: String): ByteArray
    external fun nativeFreeAudio(handle: Long)

    /** [nativeTranslateJobs] の受け渡し口。どれも推論のスレッドから呼ばれる。 */
    interface JobCallback {
        /** 次の依頼の番号。無ければ -1。 */
        fun nextJob(): Int

        /** [nextJob] が返した依頼のプロンプト。 */
        fun prompt(id: Int): String

        /** 訳文の続き（UTF-8、文字の途中で切れない）。0＝続ける、1＝この依頼だけ止める、2＝全部止める。 */
        fun onToken(id: Int, utf8: ByteArray): Int

        /** 依頼の生成が終わった。ここで次の依頼を足してよい。 */
        fun onFinished(id: Int)

        /** トークンに分けられなかったか、シーケンス1本分のコンテキストに入らないので訳さなかった。 */
        fun onTooLong(id: Int)
    }

    /**
     * [callback] から依頼を受け取り、最大 [slots] 本（[nativeLoad] の nSeqMax まで）を同時に訳す。訳し終わったシーケンスには
     * すぐ次の依頼を入れる。全部を合わせた生成トークン数、または負のエラーコード（-3 推論失敗、-4 受け渡しの失敗）。
     * トークンに分けられない・シーケンス1本分に入らない依頼はエラーにせず、[JobCallback.onTooLong] で返す。
     * 1文だけ訳すときも、slots を1にしてこれを使う（生成の処理はこの1本だけ）。
     */
    external fun nativeTranslateJobs(handle: Long, slots: Int, maxTokens: Int, callback: JobCallback): Int

    /** 性能ヒントの有無とスレッド数（0なら変えない）を切り替える（[TranslationEngine.configure]）。 */
    external fun nativeConfigure(handle: Long, boost: Boolean, nThreads: Int)
}
