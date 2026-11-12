package app.plamotranslate.overlay

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import app.plamotranslate.llm.Language
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * 画面翻訳の訳の控えを端末のデータベース（SQLite）に残す（2026-11-12、作者の要望「拡張機能と同じように、
 * データベースに保存して次回起動時にも活かす。設定から削除もできるように」。docs/05_画面翻訳.md「訳の控え」）。
 *
 * 同じゲーム・アプリの画面は同じ文が何度も出るので、一度訳した文は画面翻訳を終えても、アプリを入れ直すまで使い回す。
 * 訳は原文の言語・訳す先・背景（context）で変わるので、これらを合わせて鍵にする（[Scope]）。
 * 翻訳モデルは鍵に入れず、確かなモデル（2B）の訳を優先して1つだけ残す（[Scope] の説明）。
 *
 * 件数は [MAX_ENTRIES] までにし、超えたら最後に使った時刻が古いものから消す。
 * Androidに入っている SQLite をそのまま使い、ライブラリ（Room など）は足さない。
 */
class TranslationCache private constructor(context: Context) {
    private val helper = Helper(context.applicationContext)

    private val _entries = MutableStateFlow(-1)

    /** 控えている件数（設定の画面に出す）。まだ数えていなければ -1。 */
    val entries: StateFlow<Int> = _entries.asStateFlow()

    /**
     * 消した回数。[BatchTranslator] はメモリにも控えを持つので、これが変わったらメモリの控えも捨てる
     * （設定の画面で消しても、動いている画面翻訳が古い訳を出し続けないように）。
     */
    @Volatile
    var generation = 0
        private set

    /**
     * 1つの鍵の組（原文の言語・訳す先・背景）に絞り、[rank] のモデルから見た控え（2026-11-12、作者の指示）。
     * 訳の確かさはモデルの大きさの順（[rank] が大きいほど確か。0.8B＜2B）とし、
     * - 引くときは、自分以上のモデルの訳だけを使う。2Bは0.8Bの訳を当てにせず訳し直し、0.8Bは2Bの訳があればそのまま使う。
     * - 書くときは、自分以上のモデルの訳があれば書かない。2Bの訳は0.8Bの訳を上書きしていく。
     * 同じ原文に残る訳は1つだけで、いちばん確かなモデルのもの。[model] は記録用（どのモデルの重みで訳したか）。
     */
    inner class Scope(private val model: String, private val rank: Int, source: Language?, target: Language, context: String?) {
        private val key = listOf(source?.code ?: "auto", target.code, context.orEmpty()).joinToString("|")

        /** 控えを消した回数（[TranslationCache.generation]）。 */
        val generation: Int get() = this@TranslationCache.generation

        /** [texts] のうち、自分以上のモデルの訳が控えにあるもの。使った時刻も新しくする。 */
        suspend fun lookup(texts: Collection<String>): Map<String, String> = withContext(Dispatchers.IO) {
            if (texts.isEmpty()) return@withContext emptyMap()
            val found = HashMap<String, String>()
            val db = helper.writableDatabase
            // SQLiteの変数の上限（999）に収まるよう分けて引く
            for (part in texts.distinct().chunked(500)) {
                val marks = part.joinToString(",") { "?" }
                db.rawQuery(
                    "SELECT text, translation FROM $TABLE WHERE scope = ? AND quality >= ? AND text IN ($marks)",
                    arrayOf(key, rank.toString()) + part,
                ).use { c -> while (c.moveToNext()) found[c.getString(0)] = c.getString(1) }
            }
            if (found.isNotEmpty()) touch(db, found.keys)
            found
        }

        /**
         * 訳をまとめて控える（1回の翻訳のまとまりごとに1つのトランザクション）。空の訳は控えない（次も訳し直すため）。
         * 自分より確かなモデルの訳がある原文は書き換えない。
         */
        suspend fun storeAll(translations: Map<String, String>) = withContext(Dispatchers.IO) {
            val entries = translations.filterValues { it.isNotBlank() }
            if (entries.isEmpty()) return@withContext
            val db = helper.writableDatabase
            val now = System.currentTimeMillis()
            db.beginTransaction()
            try {
                for ((text, translation) in entries) {
                    db.execSQL(
                        "INSERT INTO $TABLE (scope, text, translation, model, quality, used_at) VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT (scope, text) DO UPDATE SET translation = excluded.translation, model = excluded.model, " +
                            "quality = excluded.quality, used_at = excluded.used_at WHERE excluded.quality >= $TABLE.quality",
                        arrayOf<Any>(key, text, translation, model, rank, now),
                    )
                }
                trim(db)
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            countNow(db)
        }

        private fun touch(db: SQLiteDatabase, texts: Collection<String>) {
            val now = System.currentTimeMillis()
            for (part in texts.chunked(500)) {
                val marks = part.joinToString(",") { "?" }
                db.execSQL("UPDATE $TABLE SET used_at = ? WHERE scope = ? AND text IN ($marks)", listOf<Any>(now, key).plus(part).toTypedArray())
            }
        }
    }

    fun scope(model: String, rank: Int, source: Language?, target: Language, context: String? = null) =
        Scope(model, rank, source, target, context)

    /** 件数を数え直す（設定の画面を開いたとき）。 */
    suspend fun refreshCount() = withContext(Dispatchers.IO) { countNow(helper.readableDatabase) }

    /** 控えをすべて消す（設定の画面の「消す」）。 */
    suspend fun clear() = withContext(Dispatchers.IO) {
        val db = helper.writableDatabase
        db.delete(TABLE, null, null)
        generation++
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
        /** 2：鍵からモデルを外し、モデルの確かさ（quality）の列を足した（2026-11-12）。 */
        private const val DB_VERSION = 2
        private const val TABLE = "translations"

        /**
         * 控える上限。Chrome拡張（IndexedDB）と同じ値。1件は原文・訳・鍵と索引で数百バイトなので、2万件で約5〜10MB。
         * 引くのは主キーの索引なので、件数が増えても1回の画面翻訳（数十件）の読み書きは数ミリ秒で済む。
         * 画面翻訳は1回に約20件なので、新しい文ばかりでも約1000回分を覚えていられる。
         */
        const val MAX_ENTRIES = 20_000
        private const val TRIM_TO = 18_000

        @Volatile
        private var instance: TranslationCache? = null

        fun get(context: Context): TranslationCache =
            instance ?: synchronized(this) { instance ?: TranslationCache(context).also { instance = it } }
    }
}
