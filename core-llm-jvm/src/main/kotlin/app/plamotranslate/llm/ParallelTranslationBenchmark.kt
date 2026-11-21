package app.plamotranslate.llm

import app.plamotranslate.pipeline.translation.JobEngine
import app.plamotranslate.pipeline.translation.JobSource

/** Android と Windows の設定で使う、同時に訳す数の計測。 */
object ParallelTranslationBenchmark {
    /**
     * [parallel] 件の文を同時に訳し、全部を合わせた速さ（トークン/秒。プロンプトの処理も含む）を返す。
     * 測れなければ null。初回だけ遅くならないよう、測る前に同じ件数で短く1回訳しておく。
     */
    suspend fun measure(engine: JobEngine, parallel: Int): Double? {
        val requests = PARALLEL_TEXTS.take(parallel).map { TranslationRequest(it, Language.JA, Language.EN) }
        // 決まった依頼を一度ずつ出すだけの出し手
        fun source() = object : JobSource {
            private var i = 0
            override fun next() = requests.getOrNull(i)?.let { (i to it).also { i++ } }
            override fun onText(id: Int, text: String) = false
            override fun onFinished(id: Int) = Unit
            override fun onTooLong(id: Int) = Unit
        }
        engine.run(parallel, source(), maxTokens = 4)
        val start = System.nanoTime()
        val tokens = engine.run(parallel, source(), maxTokens = PARALLEL_TOKENS)
        val millis = (System.nanoTime() - start) / 1_000_000
        return if (millis > 0 && tokens > 0) tokens * 1000.0 / millis else null
    }

    /** 同時に訳す数の速さを測る文。長さをそろえ、どれも [PARALLEL_TOKENS] トークンより長い訳になるようにする。 */
    private val PARALLEL_TEXTS = listOf(
        "明日の朝は九時に駅の北口で待ち合わせて、そのまま美術館へ向かいましょう。昼ご飯は近くの店を予約してあります。",
        "この製品は水に濡れた手で触らないでください。故障の原因になりますので、乾いた布で拭いてからお使いください。",
        "先週お願いした資料の件ですが、金曜日までに送っていただけると助かります。難しければ早めに教えてください。",
        "次の電車は十分ほど遅れているそうなので、先に切符を買ってから、ホームの売店で飲み物を買っておきましょう。",
        "部屋の鍵は受付で預かっています。外出するときは必ず返して、戻ったら名前と部屋番号を伝えてください。",
        "今日は午後から雨が強くなる予報です。傘を持って出かけ、帰りが遅くなるなら駅まで迎えに行きます。",
        "注文した料理がまだ届いていないようです。確認しますので、少しだけお待ちいただけますか。申し訳ありません。",
        "会議は三階の会議室に変更になりました。資料は机の上に置いてありますので、始まる前に目を通してください。",
    )
    private const val PARALLEL_TOKENS = 40
}
