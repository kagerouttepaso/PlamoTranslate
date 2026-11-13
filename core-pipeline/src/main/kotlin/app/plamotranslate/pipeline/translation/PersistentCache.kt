package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.JapaneseForm
import app.plamotranslate.llm.Language

/**
 * 訳の控えの範囲（docs/設計/翻訳コア.md「控え」）。訳を変える依頼の条件で、どの層も「範囲＋片の原文」を鍵にする。
 * 契約が [TranslationStream.Options] から作り（[TranslationStream.Options.cacheScope]）、機能も層の実装も組まない。
 * 原文の言語の自動（[source] が null）と明示は、プロンプトが変わるので別の範囲にする。
 */
data class CacheScope(val source: Language?, val target: Language, val form: JapaneseForm, val context: String?) {
    /** 永続の控えに書く文字列。 */
    val id: String get() = listOf(source?.code ?: "auto", target.code, form.code, context.orEmpty()).joinToString("|")
}

/**
 * 訳したモデル。[id] は重みの識別子（記録用）、[quality] は訳の確かさ（大きいほど確か）。
 * 控えは、今のモデル以上の確かさの訳だけを使い、より確かな訳があれば書き換えない。
 */
data class ModelOrigin(val id: String, val quality: Int)

/**
 * 永続の控え（2次の層）の差し込み口。端末のデータベースなど、流れやアプリを終えても残る控え。今は画面翻訳だけが渡す
 * （app の `SqlitePersistentCache`）。どちらも呼んだコルーチンの中で終わらせる。
 */
interface PersistentCache {
    /**
     * 控えを消した回数（世代）。進んだら、流れはメモリの控えも捨てる。
     * 流れは訳し始める前の世代を [store] に渡し、実装はそれが今と違えば書かない（消す前に始めた翻訳の訳を書き戻さない）。
     */
    val generation: Int

    /** [texts] のうち、[scope] で確かさが [minQuality] 以上の訳がある片の訳。 */
    suspend fun lookup(scope: CacheScope, minQuality: Int, texts: Collection<String>): Map<String, String>

    /** 訳し終えた片を書く。[generation] が今と違えば書かない。同じ鍵に [origin] より確かな訳があれば書き換えない。 */
    suspend fun store(scope: CacheScope, origin: ModelOrigin, generation: Int, translations: Map<String, String>)
}
