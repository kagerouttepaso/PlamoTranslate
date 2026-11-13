package app.plamotranslate.pipeline.translation

/**
 * 訳の控えの差し込み口（端末のデータベースなど、永続の控え）。画面翻訳だけが渡す（app の `SqlitePersistentCache`）。
 * どちらも呼んだコルーチンの中で終わらせる。
 */
interface PersistentCache {
    /** [texts] のうち控えにある片の訳。 */
    suspend fun lookup(texts: Collection<String>): Map<String, String>

    /** 訳し終えた片を控える。 */
    suspend fun store(translations: Map<String, String>)
}

/**
 * メモリの控え（片の原文 → 訳）。同じ機能の流れどうしで共有できる（画面翻訳は1回の翻訳ごとに流れを作り直すので、
 * サービスが1つ持って渡す）。訳したエンジン（翻訳モデル）が変わったら捨てる（[key]）。どのスレッドから触ってもよい。
 */
class MemoryCache(private val capacity: Int = 500) {
    private val map = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > capacity
    }
    private var key: Any? = null

    /**
     * 訳したエンジンを [key] にする。前と等しくなければ中身を捨てる。鍵にはエンジン（同じものかで比べる）のほか、
     * 永続の控えを消した回数なども組にして入れられる（画面翻訳：設定の画面で控えを消したらメモリの控えも捨てる）。
     */
    fun use(key: Any) = synchronized(map) {
        if (this.key != key) {
            map.clear()
            this.key = key
        }
    }

    operator fun get(text: String): String? = synchronized(map) { map[text] }
    operator fun set(text: String, translation: String) = synchronized(map) { map[text] = translation }
    fun putAll(translations: Map<String, String>) = synchronized(map) { map.putAll(translations) }
    fun clear() = synchronized(map) { map.clear() }
    val size: Int get() = synchronized(map) { map.size }
}
