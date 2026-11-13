package app.plamotranslate.translation

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.plamotranslate.pipeline.translation.CacheScope
import app.plamotranslate.pipeline.translation.CachedTranslation
import app.plamotranslate.pipeline.translation.ModelOrigin
import app.plamotranslate.pipeline.translation.PersistentCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 永続の控え（2次の層）を端末のデータベース（SQLite）に置く実装。今は画面翻訳だけが使う（docs/設計/画面翻訳.md「訳の控え」）。
 * 同じゲーム・アプリの画面は同じ文が何度も出るので、一度訳した文は画面翻訳を終えても、アプリを入れ直すまで使い回す。
 *
 * 鍵・確かさ・世代の約束は差し込み口（[PersistentCache]）と docs/設計/翻訳コア.md「控え」に従う。鍵の範囲は契約が作り
 * （[CacheScope.id] を列に入れる）、同じ原文に残る訳は、いちばん確かなモデルの1つだけ。
 *
 * 件数は [MAX_ENTRIES] までにし、超えたら最後に使った時刻が古いものから消す。
 * Androidに入っている SQLite をそのまま使い、ライブラリ（Room など）は足さない。
 */
class SqlitePersistentCache private constructor(context: Context) : PersistentCache {
    private val helper = Helper(context.applicationContext)

    private val _entries = MutableStateFlow(-1)

    /** 控えている件数（設定の画面に出す）。まだ数えていなければ -1。 */
    val entries: StateFlow<Int> = _entries.asStateFlow()

    /** 消した回数（[PersistentCache.generation]）。 */
    @Volatile
    override var generation = 0
        private set

    /** [texts] のうち、確かさが [minQuality] 以上の訳が控えにあるもの。使った時刻も新しくする。 */
    override suspend fun lookup(scope: CacheScope, minQuality: Int, texts: Collection<String>): Map<String, CachedTranslation> = withContext(Dispatchers.IO) {
        if (texts.isEmpty()) return@withContext emptyMap()
        val key = scope.id
        val found = HashMap<String, CachedTranslation>()
        val db = helper.writableDatabase
        // SQLiteの変数の上限（999）に収まるよう分けて引く
        for (part in texts.distinct().chunked(500)) {
            val marks = part.joinToString(",") { "?" }
            db.rawQuery(
                "SELECT text, translation, quality FROM $TABLE WHERE scope = ? AND quality >= ? AND text IN ($marks)",
                arrayOf(key, minQuality.toString()) + part,
            ).use { c -> while (c.moveToNext()) found[c.getString(0)] = CachedTranslation(c.getString(1), c.getInt(2)) }
        }
        if (found.isNotEmpty()) touch(db, key, found.keys)
        found
    }

    /**
     * 訳をまとめて書く（1回の実行ごとに1つのトランザクション）。空の訳は書かない（次も訳し直すため）。
     * より確かなモデルの訳がある原文は書き換えない。[generation] が今と違えば書かない（消す前に始めた翻訳の訳を書き戻さない）。
     */
    override suspend fun store(scope: CacheScope, origin: ModelOrigin, generation: Int, translations: Map<String, String>) = withContext(Dispatchers.IO) {
        val entries = translations.filterValues { it.isNotBlank() }
        if (entries.isEmpty()) return@withContext
        val key = scope.id
        val db = helper.writableDatabase
        val now = System.currentTimeMillis()
        db.beginTransaction()
        try {
            // トランザクションの中で確かめる。[clear] は世代を進めてから消すので、確かめた後に消されても、書いた分ごと消える
            if (generation != this@SqlitePersistentCache.generation) return@withContext
            for ((text, translation) in entries) {
                db.execSQL(
                    "INSERT INTO $TABLE (scope, text, translation, model, quality, used_at) VALUES (?, ?, ?, ?, ?, ?) " +
                        "ON CONFLICT (scope, text) DO UPDATE SET translation = excluded.translation, model = excluded.model, " +
                        "quality = excluded.quality, used_at = excluded.used_at WHERE excluded.quality >= $TABLE.quality",
                    arrayOf<Any>(key, text, translation, origin.id, origin.quality, now),
                )
            }
            trim(db)
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        countNow(db)
    }

    private fun touch(db: SQLiteDatabase, key: String, texts: Collection<String>) {
        val now = System.currentTimeMillis()
        for (part in texts.chunked(500)) {
            val marks = part.joinToString(",") { "?" }
            db.execSQL("UPDATE $TABLE SET used_at = ? WHERE scope = ? AND text IN ($marks)", listOf<Any>(now, key).plus(part).toTypedArray())
        }
    }

    /** 件数を数え直す（設定の画面を開いたとき）。 */
    suspend fun refreshCount() = withContext(Dispatchers.IO) { countNow(helper.readableDatabase) }

    /** 控えをすべて消す（設定の画面の「消す」）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        // 先に回数を増やす（書いている途中の翻訳が、消した後に書き戻さないように。[store]）
        generation++
        db.delete(TABLE, null, null)
        // 消した分の領域を返す（数万件で数MBになるため）
        db.execSQL("VACUUM")
        countNow(db)
    }

    private fun countNow(db: SQLiteDatabase) {
        db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) _entries.value = c.getInt(0) }
    }

    /** [MAX_ENTRIES] を超えたら、最後に使った時刻が古いものから消して [TRIM_TO] 件にする（毎回消すと遅いので余裕を持たせる）。 */
    private fun trim(db: SQLiteDatabase) {
        val count = db.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
        if (count <= MAX_ENTRIES) return
        db.execSQL(
            "DELETE FROM $TABLE WHERE rowid IN (SELECT rowid FROM $TABLE ORDER BY used_at ASC LIMIT ?)",
            arrayOf<Any>(count - TRIM_TO),
        )
    }

    private class Helper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                "CREATE TABLE $TABLE (scope TEXT NOT NULL, text TEXT NOT NULL, translation TEXT NOT NULL, " +
                    "model TEXT NOT NULL, quality INTEGER NOT NULL, used_at INTEGER NOT NULL, PRIMARY KEY (scope, text))",
            )
            db.execSQL("CREATE INDEX ${TABLE}_used_at ON $TABLE (used_at)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            // 控えなので、形を変えたら捨てて作り直す
            db.execSQL("DROP TABLE IF EXISTS $TABLE")
            onCreate(db)
        }
    }

    companion object {
        private const val DB_NAME = "translation_cache.db"
        /** 2：鍵からモデルを外し、モデルの確かさ（quality）の列を足した。3：鍵の範囲に form を足した（[CacheScope.id]）。 */
        private const val DB_VERSION = 3
        private const val TABLE = "translations"

        /**
         * 控える上限。Chrome拡張（IndexedDB）と同じ値。1件は原文・訳・鍵と索引で数百バイトなので、2万件で約5〜10MB。
         * 引くのは主キーの索引なので、件数が増えても1回の画面翻訳（数十件）の読み書きは数ミリ秒で済む。
         * 画面翻訳は1回に約20件なので、新しい文ばかりでも約1000回分を覚えていられる。
         */
        const val MAX_ENTRIES = 20_000
        private const val TRIM_TO = 18_000

        @Volatile
        private var instance: SqlitePersistentCache? = null

        fun get(context: Context): SqlitePersistentCache =
            instance ?: synchronized(this) { instance ?: SqlitePersistentCache(context).also { instance = it } }
    }
}
