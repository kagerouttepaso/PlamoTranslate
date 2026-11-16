package app.plamotranslate.pipeline.translation

/**
 * メモリのキャッシュ（1次の層。docs/設計/翻訳コア.md「キャッシュ」）。キーは永続キャッシュと同じ「範囲＋チャンクの原文」で、訳と訳の品質を持つ。
 * ストリームごとに作るか、機能が持ってストリームどうしで共有する（画面翻訳は1回の翻訳ごとにストリームを作り直すので、サービスが1つ持って渡す）。
 * 範囲がキーに入るので、範囲の違うストリームどうしで共有しても混ざらない。
 *
 * ストリームはロックの中や推論のスレッドから同期で引く（訳している途中に頼まれたチャンクは、この層だけを見る）ので、インターフェースにせず、
 * 中核の具体的な型にする。どのスレッドから触ってもよい。
 */
class MemoryCache(private val capacity: Int = 500) {
    private class Entry(val translation: String, val quality: Int)

    private val map = object : LinkedHashMap<Pair<CacheScope, String>, Entry>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Pair<CacheScope, String>, Entry>?) = size > capacity
    }
    private var generation: Int? = null

    /** 永続キャッシュの世代に合わせる。前と違えば（設定の画面でキャッシュを消した）中身を捨てる。 */
    fun sync(generation: Int) = synchronized(map) {
        if (this.generation != generation) {
            map.clear()
            this.generation = generation
        }
    }

    /** [scope] の [text] の訳のうち、品質が [minQuality] 以上のもの。 */
    fun get(scope: CacheScope, text: String, minQuality: Int): String? = synchronized(map) {
        map[scope to text]?.takeIf { it.quality >= minQuality }?.translation
    }

    /** [scope] の [text] の訳を入れる。より品質の高い訳があれば書き換えない。 */
    fun put(scope: CacheScope, text: String, translation: String, quality: Int) = synchronized(map) {
        val key = scope to text
        val old = map[key]
        if (old == null || old.quality <= quality) map[key] = Entry(translation, quality)
    }

    fun clear() = synchronized(map) { map.clear() }
    val size: Int get() = synchronized(map) { map.size }
}
