package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.pipeline.translation.JobSource
import app.plamotranslate.pipeline.translation.SingleRequest

/**
 * 翻訳の速さを測る（設定の「速さを測る」「自動で調整」）。決まった文を訳し、最初の文字までの時間と生成の速さを出す。
 * 機能の翻訳の流れではなく、エンジンの差し込み口（[TranslationEngine.run]）を直接使う計測の道具。
 */
object EngineBenchmark {
    /** 測った結果。[firstMillis] は依頼から最初の文字まで（プロンプトの処理を含む）。 */
    data class Result(val firstMillis: Long, val tokens: Int, val totalMillis: Long) {
        /** 生成の速さ（最初の文字の後）。 */
        val tokensPerSecond: Double
            get() = if (tokens > 1 && totalMillis > firstMillis) (tokens - 1) * 1000.0 / (totalMillis - firstMillis) else 0.0
    }

    /** 今の翻訳モデルで1文を測る。モデルが読めない・訳が終わらなければ null。 */
    suspend fun run(context: Context): Result? {
        val engine = EngineHolder.awaitEngine(context) ?: return null
        val source = SingleRequest(
            TranslationRequest(
                "明日の朝は九時に駅の北口で待ち合わせて、そのまま美術館へ向かいましょう。昼ご飯は近くの店を予約してあります。",
                Language.JA, Language.EN,
            ),
        )
        val start = System.nanoTime()
        val tokens = engine.run(1, source, maxTokens = 64)
        val total = (System.nanoTime() - start) / 1_000_000
        if (source.firstTextAt == 0L) return null
        return Result((source.firstTextAt - start) / 1_000_000, tokens, total)
    }

    /**
     * [parallel] 件の文を同時に訳し、全部を合わせた速さ（トークン/秒。プロンプトの処理も含む）を返す。
     * 測れなければ null。初回だけ遅くならないよう、測る前に同じ件数で短く1回訳しておく。
     */
    suspend fun runParallel(context: Context, parallel: Int): Double? {
        val engine = EngineHolder.awaitEngine(context) ?: return null
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

/**
 * 同時に訳す数の自動調整（2026-11-12、作者の要望「順に全部試すのではなく、少ない手数で最適な設定に入る形に」）。
 * 候補は1・2・4・8本。全部は測らず、3回で決める。
 *
 * 1. まず4本を測る（REDMAGICで測った最適値に近い真ん中）。これを最初の設定として入れる。
 * 2. 1本を測る（比べる基準）。
 * 3. 4本が1本より [GAIN] 以上速ければ上の8本を、そうでなければ下の2本を測る。
 *
 * 決め方：一番速い本数を選ぶ。ただし、少ない本数でも一番速い値の [NEAR] 以上出ていれば、少ない方を選ぶ
 * （同時に訳す数が多いほど、1つのまとまりが短くなって前後の文が訳の手がかりにならず、メモリも使う）。
 */
object ParallelTuner {
    private const val GAIN = 1.1
    private const val NEAR = 0.9

    /** 次に測る本数。決まったら null。[measured] は本数ごとの速さ（測れなかった本数は0）。 */
    fun next(measured: Map<Int, Double>): Int? = when {
        4 !in measured -> 4
        1 !in measured -> 1
        measured.size >= 3 -> null
        measured.getValue(4) >= measured.getValue(1) * GAIN -> 8
        else -> 2
    }

    /** これまでに測った中で選ぶ本数。 */
    fun best(measured: Map<Int, Double>): Int {
        val top = measured.values.maxOrNull() ?: return TranslationEngine.DEFAULT_PARALLEL
        if (top <= 0.0) return 1
        return measured.filterValues { it >= top * NEAR }.keys.min()
    }
}
