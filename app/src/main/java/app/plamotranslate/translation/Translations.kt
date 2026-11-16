package app.plamotranslate.translation

import android.content.Context
import app.plamotranslate.EngineHolder
import app.plamotranslate.pipeline.translation.MemoryCache
import app.plamotranslate.pipeline.translation.PersistentCache
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope

/**
 * 機能が翻訳のストリーム（中核の [TranslationStream]）を開く入口（docs/設計/翻訳コア.md「翻訳の契約」）。
 * テキスト・会話・画面・カメラの翻訳は、どれもここからストリームを開き、命令（submit・cancel・clear・close）を出して結果（updates）を購読する。
 *
 * エンジンは [EngineHolder] が持つ今の翻訳モデルを使い、モデルを切り替えたら次の依頼から新しいモデルで訳す。
 * キャッシュはモデルを切り替えても捨てず、新しいモデルの品質以上の訳だけを使う（docs/設計/翻訳コア.md「キャッシュ」）。
 */
object Translations {
    /**
     * ストリームを開く。機能が決めるのは、どの層を使い、どれだけ長く持つかだけ（キーの範囲・引き方・書き方は契約が決める）。
     * [cache] を渡すと、メモリのキャッシュをほかのストリームと共有する（画面翻訳は1回の翻訳ごとにストリームを開くので、サービスが1つ持って渡す）。
     * [persistent] を渡すと、永続キャッシュ（画面翻訳のデータベース）も使う。
     */
    fun open(
        scope: CoroutineScope,
        context: Context,
        options: TranslationStream.Options,
        cache: MemoryCache = MemoryCache(),
        persistent: PersistentCache? = null,
    ): TranslationStream {
        val app = context.applicationContext
        return TranslationStream(scope, {
            EngineHolder.awaitReady(app)?.let { ready -> TranslationStream.Backend(ready.engine, ready.model.origin, persistent) }
        }, options, cache)
    }
}
