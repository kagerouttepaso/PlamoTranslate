package app.plamotranslate.llm

/** llama.cppのJNI入口。呼び出しは [TranslationEngine] が1本のスレッドに直列化する。 */
internal object LlamaNative {
    init {
        System.loadLibrary("plamo-llm")
    }

    /** 1トークン分の文字列（UTF-8、文字の途中で切れない）を受け取る。false を返すと生成を止める。 */
    fun interface TokenCallback {
        fun onToken(utf8: ByteArray): Boolean
    }

    /**
     * 計算部分のライブラリを [nativeLibDir] から読み込む。[loadGpu] が false ならCPUの部分だけ。
     * 最初の1回だけ有効で、GPUのデバイスが使えるようになったかを返す。[nativeLoad] より前に呼ぶ。
     */
    external fun nativeInit(nativeLibDir: String, loadGpu: Boolean, cpuVariant: String): Boolean

    /** 読み込んだCPUの計算部分のファイル名（libggml-cpu-*.so）。GPUの端末で自動選択したときは空。 */
    external fun nativeCpuBackend(): String

    /** 失敗したら 0 を返す。 */
    external fun nativeLoad(
        path: String, nCtx: Int, nThreads: Int, nGpuLayers: Int,
        poll: Int, useThreadPool: Boolean, useMmap: Boolean, boost: Boolean, nSeqMax: Int,
    ): Long
    external fun nativeFree(handle: Long)

    /** 生成したトークン数、または負のエラーコード（-1 トークン化失敗、-2 長すぎる、-3 推論失敗）。 */
    external fun nativeTranslate(handle: Long, prompt: String, maxTokens: Int, callback: TokenCallback): Int

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

        /** 流れ1本分のコンテキストに入らないので訳さなかった。 */
        fun onTooLong(id: Int)
    }

    /**
     * [callback] から依頼を受け取り、最大 [slots] 本（[nativeLoad] の nSeqMax まで）を同時に訳す。訳し終わった流れには
     * すぐ次の依頼を入れる。全部を合わせた生成トークン数、または負のエラーコード（[nativeTranslate] と同じ）。
     */
    external fun nativeTranslateJobs(handle: Long, slots: Int, maxTokens: Int, callback: JobCallback): Int

    /** 計測用：性能ヒントの有無とスレッド数（0なら変えない）を切り替える。 */
    external fun nativeConfigure(handle: Long, boost: Boolean, nThreads: Int)
}
