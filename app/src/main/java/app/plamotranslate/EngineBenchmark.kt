package app.plamotranslate

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.ParallelTranslationBenchmark
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationRequest
import app.plamotranslate.pipeline.translation.SingleRequest

/**
 * 翻訳の速さを測る（設定の「速さを測る」「自動で調整」）。決まった文を訳し、最初の文字までの時間と生成の速さを出す。
 * 機能の翻訳のストリームではなく、エンジンのインターフェース（[TranslationEngine.run]）を直接使う計測の道具。
 */
object EngineBenchmark {
    /** 測った結果。[firstMillis] は依頼から最初の文字まで（プロンプトの処理を含む）。 */
    data class Result(val firstMillis: Long, val tokens: Int, val totalMillis: Long, val text: String = "") {
        /** 生成の速さ（最初の文字の後）。 */
        val tokensPerSecond: Double
            get() = if (tokens > 1 && totalMillis > firstMillis) (tokens - 1) * 1000.0 / (totalMillis - firstMillis) else 0.0
    }

    /** 今の翻訳モデルで1文を測る。モデルが読めない・訳が終わらなければ null。 */
    suspend fun run(context: Context): Result? {
        val engine = EngineHolder.awaitEngine(context)?.first ?: return null
        return measureSingle(engine)
    }

    /** 指定したエンジンで1文を測る（別プロセスのバックエンド比較でも使う）。 */
    suspend fun measureSingle(engine: TranslationEngine): Result? {
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
        return Result((source.firstTextAt - start) / 1_000_000, tokens, total, source.text.trim())
    }

    /** 今の翻訳モデルで、同時に [parallel] 件訳す速さを測る。 */
    suspend fun runParallel(context: Context, parallel: Int): Double? {
        val engine = EngineHolder.awaitEngine(context)?.first ?: return null
        return measureParallel(engine, parallel)
    }

    /** 指定したエンジンで、同時に [parallel] 件訳す速さを測る。 */
    suspend fun measureParallel(engine: TranslationEngine, parallel: Int): Double? =
        ParallelTranslationBenchmark.measure(engine, parallel)
}
