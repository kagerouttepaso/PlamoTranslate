package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.TranslationRequest

/**
 * 翻訳のエンジンの差し込み口（docs/13_画面翻訳の構成.md「翻訳の契約」）。llama.cpp のエンジン（core-llm の
 * `TranslationEngine`）が実装する。中核はこの口に対して書くので、単体テストでは偽のエンジンに差し替えられる。
 */
interface JobEngine {
    /** 同時に訳す依頼の数の設定（1なら1件ずつ）。設定の画面で変えられる。 */
    val parallel: Int

    /**
     * [source] から依頼を受け取り、最大 [slots] 本を同時に訳す。訳し終わった流れには、ほかの流れを待たずに次の依頼を入れる。
     * [source] に依頼が無くなり、全部の流れが訳し終えたら戻る。戻り値は生成したトークン数の合計。
     * 呼んだコルーチンが取り消されたら、次のトークンで全部止める。
     */
    suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int
}

/**
 * [JobEngine.run] に依頼を出す口。どのメソッドもエンジンの推論のスレッドから順に呼ばれる（同時には呼ばれない）。
 * 出し手の中の状態をほかのスレッドからも触るなら、出し手の側で同期する。
 */
interface JobSource {
    /** 次に訳す依頼と、その番号（出し手が決める）。今は無ければ null。 */
    fun next(): Pair<Int, TranslationRequest>?

    /** [id] の訳文の続き。true を返すと、その依頼の生成を止める（打ち切り・取り消し）。 */
    fun onText(id: Int, text: String): Boolean

    /** [id] の生成が終わった（止めた場合も含む）。ここで足した依頼は、空いた流れですぐ訳される。 */
    fun onFinished(id: Int)

    /** [id] は流れ1本分のコンテキストに入らないので訳さなかった。流れを1本にすれば訳せることがある。 */
    fun onTooLong(id: Int)
}

/**
 * 依頼を1つだけ出す口（読み込み直後の空訳しと、速さの計測に使う）。機能の翻訳は [TranslationStream] を使う。
 */
class SingleRequest(private val request: TranslationRequest) : JobSource {
    private var sent = false
    private val output = StringBuilder()

    /** 最初の文字が届いた時刻（System.nanoTime）。届かなければ0。 */
    @Volatile
    var firstTextAt = 0L
        private set

    /** 届いた文字の回数（おおよそ生成したトークンの数）。 */
    @Volatile
    var pieces = 0
        private set

    val text: String get() = synchronized(output) { output.toString() }

    override fun next(): Pair<Int, TranslationRequest>? = if (sent) null else (0 to request).also { sent = true }

    override fun onText(id: Int, text: String): Boolean {
        if (firstTextAt == 0L) firstTextAt = System.nanoTime()
        pieces++
        synchronized(output) { output.append(text) }
        return false
    }

    override fun onFinished(id: Int) = Unit
    override fun onTooLong(id: Int) = Unit
}
