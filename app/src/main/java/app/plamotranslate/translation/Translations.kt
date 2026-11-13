package app.plamotranslate.translation

import android.content.Context
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.pipeline.translation.MemoryCache
import app.plamotranslate.pipeline.translation.PersistentCache
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope

/**
 * 機能が翻訳の流れ（中核の [TranslationStream]）を開く入口（docs/設計/翻訳コア.md「翻訳の契約」）。
 * テキスト・会話・画面・カメラの翻訳は、どれもここから流れを開き、命令（submit・cancel・clear・close）を出して結果（updates）を購読する。
 *
 * エンジンは [EngineHolder] が持つ今の翻訳モデルを使い、モデルを切り替えたら次の依頼から新しいモデルで訳す
 * （メモリの控えは、訳したエンジンが変わったら捨てる）。
 */
object Translations {
    /**
     * 流れを開く。[persistent] を渡すと、今の翻訳モデルに合わせた永続の控え（画面翻訳のデータベース）も使う。
     * [cache] を渡すと、メモリの控えをほかの流れと共有する（画面翻訳は1回の翻訳ごとに流れを開くので、サービスが1つ持って渡す）。
     */
    fun open(
        scope: CoroutineScope,
        context: Context,
        options: TranslationStream.Options,
        cache: MemoryCache = MemoryCache(),
        persistent: ((ModelState.Ready) -> PersistentMemory?)? = null,
    ): TranslationStream {
        val app = context.applicationContext
        return TranslationStream(scope, {
            EngineHolder.awaitReady(app)?.let { ready ->
                val memory = persistent?.invoke(ready)
                // 永続の控えを消したら（設定の画面）、メモリの控えも捨てる
                TranslationStream.Backend(ready.engine, ready.engine to memory?.generation, memory?.memory)
            }
        }, options, cache)
    }

    /** 永続の控えと、それを消した回数（変わったらメモリの控えを捨てる）。 */
    class PersistentMemory(val memory: PersistentCache, val generation: Int)
}
