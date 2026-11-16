package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.JapaneseForm
import app.plamotranslate.llm.Language

/**
 * 翻訳のキャッシュの範囲（docs/設計/翻訳コア.md「キャッシュ」）。訳を変える依頼の条件で、どの層も「範囲＋チャンクの原文」を鍵にする。
 * 契約が [TranslationStream.Options] から作り（[TranslationStream.Options.cacheScope]）、機能も層の実装も組まない。
 * 原文の言語の自動（[source] が null）と明示は、プロンプトが変わるので別の範囲にする。
 */
data class CacheScope(val source: Language?, val target: Language, val form: JapaneseForm, val context: String?) {
    /** 永続キャッシュに書く文字列。 */
    val id: String get() = listOf(source?.code ?: "auto", target.code, form.code, context.orEmpty()).joinToString("|")
}

/**
 * 訳したモデル。[id] は重みの識別子（記録用）、[quality] は訳の確かさ（大きいほど確か）。
 * キャッシュは、今のモデル以上の確かさの訳だけを使い、より確かな訳があれば書き換えない。
 */
data class ModelOrigin(val id: String, val quality: Int)

/** キャッシュにある訳と、それを訳したモデルの確かさ。層から層へ移しても確かさを保つ。 */
data class CachedTranslation(val translation: String, val quality: Int)

/**
 * 永続キャッシュ（2次の層）のインターフェース。端末のデータベースなど、ストリームやアプリを終えても残るキャッシュ。今は画面翻訳だけが渡す
 * （app の `SqlitePersistentCache`）。どちらも呼んだコルーチンの中で終わらせる。
 */
interface PersistentCache {
    /**
     * キャッシュを消した回数（世代）。進んだら、ストリームはメモリのキャッシュも捨てる。
     * ストリームは訳し始める前の世代を [store] に渡し、実装はそれが今と違えば書かない（消す前に始めた翻訳の訳を書き戻さない）。
     */
    val generation: Int

    /** [texts] のうち、[scope] で確かさが [minQuality] 以上の訳があるチャンクの訳と、その確かさ。 */
    suspend fun lookup(scope: CacheScope, minQuality: Int, texts: Collection<String>): Map<String, CachedTranslation>

    /** 訳し終えたチャンクを書く。[generation] が今と違えば書かない。同じ鍵に [origin] より確かな訳があれば書き換えない。 */
    suspend fun store(scope: CacheScope, origin: ModelOrigin, generation: Int, translations: Map<String, String>)
}
