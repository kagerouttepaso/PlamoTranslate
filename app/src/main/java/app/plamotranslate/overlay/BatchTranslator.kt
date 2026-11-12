package app.plamotranslate.overlay

import android.util.Log
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * 画面のブロックを数行ずつまとめて1回で訳す。
 * 1ブロックずつ訳すと毎回プロンプト処理の固定費がかかり、1画面（20〜30ブロック）で20秒を超えた（2026-11-05実測）。
 * PLaMoは改行を保ったまま訳すので、1ブロック＝1行にして送り、訳文を改行で分けて各ブロックへ戻す。
 *
 * 2026-11-12に、Chrome拡張「PLaMo翻訳」で足された対策を持ち込んだ（作者の採用。[TranslationChecks]）。
 * - 同じ原文は1回だけ訳し、同じ文のブロックすべてに配る（一覧や表で同じ語が並ぶ画面）。
 * - 長いブロック（[TranslationChecks.LONG_TEXT] 字超）は文の切れ目で片に分けて訳し、つないで返す。
 * - 行数が合わないときはそのまとまりを、行数が合っても訳されていない行（[TranslationChecks.untranslated]）はその行を、1つずつ訳し直す。
 * - 同じ並びを繰り返して止まらなくなった生成は打ち切る（[TranslationChecks.runaway]）。
 * - 控え（メモリ・データベース）の当たり方をログに出す。
 *
 * 2026-11-12に、まとまりを同時に訳すようにした（作者の要望。[TranslationEngine.parallel]、設定の画面の「同時に訳す数」）。
 * 生成は1トークンずつしか進まないが、複数のまとまりを1回の計算で一緒に進めると合計が速い
 * （REDMAGICのGPUで4本なら約1.7倍。docs/03_翻訳コア.md「複数の文を同時に訳す」）。まとまりは仕事の列（[Jobs]）にし、
 * 訳し終わった流れにすぐ次のまとまりを入れる。
 *
 * [source] は原文の言語。null なら PLaMo に判定させる（自動）。訳の控えは原文の言語を前提にするので、
 * 言語を変えたら作り直す。
 */
class BatchTranslator(
    /** 訳すエンジン。モデルを切り替えるとエンジンが変わるので、呼ぶ側はこれが同じかを見て作り直す。 */
    val engine: TranslationEngine,
    val target: Language,
    val source: Language? = null,
    /** 原文の背景（PFML3の context）。カメラの表・メニューのモードで、短い語の意味を決める手がかりに渡す。 */
    val context: String? = null,
    /**
     * 端末のデータベースに残す控え（画面翻訳だけが渡す。[TranslationCache]）。null ならメモリの控えだけで、
     * 画面翻訳を終えると消える（カメラ翻訳と評価）。鍵の組（モデル・言語・背景）は呼ぶ側がこのインスタンスの条件に合わせる。
     */
    val persistent: TranslationCache.Scope? = null,
) {

    /** 同じ文を何度も訳さないための控え。画面を撮り直したときに効く。 */
    private val cache = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > CACHE_SIZE
    }

    /** メモリの控えを作ったときの、データベースの控えを消した回数。設定の画面で消されたらメモリの控えも捨てる。 */
    private var generation = persistent?.generation ?: 0

    /**
     * [texts] を訳す。[onDelta] は訳文の続きを、[onReplace] は確定した訳文の全体を、それぞれブロック番号付きで受け取る。
     * 控え（メモリ → データベース）にある文は訳さずにそのまま返す。
     */
    suspend fun translate(
        texts: List<String>,
        onDelta: (index: Int, text: String) -> Unit,
        onReplace: (index: Int, text: String) -> Unit,
    ) {
        persistent?.let { p -> if (p.generation != generation) { cache.clear(); generation = p.generation } }
        val parallel = engine.parallel
        // 同時に訳すときは、長いブロックを文の切れ目で細かく分け、分けた片をほかの流れでも訳す（2026-11-12、
        // Discordのボットの長文が最後に1本だけで訳されて、1画面に7〜9秒かかった）。1件ずつなら今までどおり長い段落だけ分ける
        val run = Run(texts, onDelta, onReplace, if (parallel > 1) TranslationChecks.PARALLEL_SPLIT else TranslationChecks.LONG_TEXT)

        // 控え：メモリにないものをデータベースからまとめて引き、メモリへ移す
        val memoryHits = run.units.count { cache[it] != null }
        val fromDb = persistent?.lookup(run.units.filter { cache[it] == null })?.also { cache.putAll(it) }.orEmpty()
        val pending = mutableListOf<Int>()
        for (u in run.units.indices) {
            val cached = cache[run.units[u]]
            if (cached != null) run.replace(u, cached) else pending += u
        }
        // 同時に訳す数（設定の画面の「同時に訳す数」）の流れで、まとまりを読み順に訳す。訳し終わった流れには、すぐ次の
        // まとまり（訳し直しを含む）を入れる。訳の書き換えは、推論のスレッドから受け取って、この呼び出しの側で行う
        val jobs = Jobs(run, chunked(pending, run.units, parallel))
        coroutineScope {
            launch {
                try {
                    engine.translateJobs(parallel, jobs)
                    // 同時に訳すと流れ1本分のコンテキストに入らなかったまとまりは、1本でコンテキストを全部使って訳す
                    if (parallel > 1 && jobs.requeueTooLong()) engine.translateJobs(1, jobs)
                    jobs.giveUpTooLong()
                } finally {
                    jobs.ui.close()
                }
            }
            for (action in jobs.ui) action()
        }
        Log.i(
            TAG,
            "まとめ訳し: ${texts.size}件（重複を除いて${run.units.size}片）、控え メモリ${memoryHits}・データベース${fromDb.size}、" +
                "訳した${pending.size}片（同時${parallel}、訳し直し${run.retried}、打ち切り${run.stopped}）",
        )
    }

    /**
     * 1回の [translate] の作業。ブロックを片（長いブロックは文の切れ目で分ける）にし、同じ片は1つにまとめる。
     * 訳は片ごとに持ち、片が変わるたびに、その片を含むブロックへ知らせる。
     */
    private inner class Run(
        texts: List<String>,
        private val onDelta: (Int, String) -> Unit,
        private val onReplace: (Int, String) -> Unit,
        /** ブロックを文の切れ目で分ける字数の目安（[TranslationChecks.splitLong]）。 */
        splitAt: Int,
    ) {
        /** ブロックごとの片（片の番号）。 */
        private val pieces: List<List<Int>>

        /** 重複を除いた片の原文。 */
        val units: List<String>

        /** 片ごとの今の訳（途中を含む）。 */
        private val current: Array<StringBuilder>

        /** 片を含むブロック。 */
        private val owners: List<List<Int>>

        var retried = 0
        var stopped = 0

        init {
            val index = LinkedHashMap<String, Int>()
            pieces = texts.map { t -> TranslationChecks.splitLong(t, splitAt).map { p -> index.getOrPut(p) { index.size } } }
            units = index.keys.toList()
            current = Array(units.size) { StringBuilder() }
            val o = List(units.size) { mutableListOf<Int>() }
            pieces.forEachIndexed { block, ps -> ps.distinct().forEach { o[it] += block } }
            owners = o
        }

        fun delta(unit: Int, text: String) {
            current[unit].append(text)
            for (b in owners[unit]) if (pieces[b].size == 1) onDelta(b, text) else onReplace(b, joined(b))
        }

        fun replace(unit: Int, text: String) {
            current[unit].setLength(0)
            current[unit].append(text)
            for (b in owners[unit]) onReplace(b, if (pieces[b].size == 1) text else joined(b))
        }

        private fun joined(block: Int) = TranslationChecks.joinPieces(pieces[block].map { current[it].toString() })
    }

    /**
     * 1回の [translate] の、訳す仕事の列（[TranslationEngine.JobSource]）。まとまりを読み順に出し、訳し終わったまとまりの
     * 行数が合わない・訳されていない片は、1片ずつの仕事にして列の先頭へ足す（空いた流れですぐ訳す）。
     *
     * 仕事の列と仕事の中身（訳文の途中・打ち切り）は推論のスレッドだけが触る。訳の書き換え（[Run]）・控えの更新は、
     * [ui] に入れて [translate] の呼び出し側で行う（画面の更新は呼んだ側のスレッドで行うため）。
     */
    private inner class Jobs(private val run: Run, chunks: List<List<Int>>) : TranslationEngine.JobSource {
        val ui = Channel<suspend () -> Unit>(Channel.UNLIMITED)
        private val queue = ArrayDeque<Work>()
        private val active = HashMap<Int, Work>()
        private val tooLong = mutableListOf<Work>()
        private var nextId = 0

        init {
            chunks.forEach { queue.addLast(Work(it)) }
        }

        /** 1回の生成。[units] の片を改行でつないで訳し、訳文を改行で分けて片へ戻す。 */
        inner class Work(val units: List<Int>) {
            val id = nextId++
            val text = units.joinToString("\n") { run.units[it] }
            val output = StringBuilder()
            var line = 0

            /** 打ち切る位置。0なら打ち切らない。 */
            var cut = 0
        }

        override fun next(): Pair<Int, TranslationRequest>? {
            val w = queue.removeFirstOrNull() ?: return null
            active[w.id] = w
            return w.id to TranslationRequest(w.text, source = source, target = target, context = context)
        }

        override fun onText(id: Int, text: String): Boolean {
            val w = active[id] ?: return true
            if (w.cut > 0) return true
            w.output.append(text)
            // 改行のたびに次の行（片）へ移る
            val parts = ArrayList<Pair<Int, String>>()
            text.split('\n').forEachIndexed { k, part ->
                if (k > 0) w.line++
                if (part.isNotEmpty() && w.line < w.units.size) parts += w.units[w.line] to part
            }
            if (parts.isNotEmpty()) post { parts.forEach { (u, part) -> run.delta(u, part) } }
            w.cut = TranslationChecks.runaway(w.output.toString(), w.units.size, w.text.length)
            return w.cut > 0
        }

        override fun onFinished(id: Int) {
            val w = active.remove(id) ?: return
            val output = if (w.cut > 0) w.output.substring(0, w.cut) else w.output.toString()
            val finals = ArrayList<Pair<Int, String>>()
            val redo = ArrayList<Int>()
            if (w.units.size == 1) {
                finals += w.units[0] to output.trim()
            } else {
                val lines = output.trim().split('\n').map { it.trim() }
                if (lines.size == w.units.size) {
                    w.units.forEachIndexed { k, u ->
                        // 行数が合っても、原文のまま写した行は訳し直す（言語の混ざったまとまりで起きる）
                        if (TranslationChecks.untranslated(run.units[u], lines[k], target)) redo += u else finals += u to lines[k]
                    }
                } else {
                    // 行がずれたら、このまとまりは1つずつ訳し直す
                    redo += w.units
                }
            }
            // 訳し直しは、待っているまとまりより先に訳す（読み順で先の片を早く仕上げる）
            redo.asReversed().forEach { queue.addFirst(Work(listOf(it))) }
            val stopped = w.cut > 0
            post {
                if (stopped) run.stopped++
                redo.forEach { u ->
                    run.retried++
                    run.replace(u, "")
                }
                finish(finals)
            }
        }

        override fun onTooLong(id: Int) {
            active.remove(id)?.let { tooLong += it }
        }

        /** 流れ1本分に入らなかったまとまりを、列へ戻す。戻したものがあれば true。推論が止まっている間に呼ぶ。 */
        fun requeueTooLong(): Boolean {
            if (tooLong.isEmpty()) return false
            tooLong.forEach { queue.addLast(Work(it.units)) }
            tooLong.clear()
            return true
        }

        /** コンテキストを全部使っても入らなかった片は、空の訳にする。推論が止まっている間に呼ぶ。 */
        fun giveUpTooLong() {
            if (tooLong.isEmpty()) return
            val units = tooLong.flatMap { it.units }
            tooLong.clear()
            Log.w(TAG, "まとめ訳し: ${units.size}片は長すぎて訳せませんでした")
            post { finish(units.map { it to "" }) }
        }

        private fun post(action: suspend () -> Unit) {
            ui.trySend(action)
        }

        /** 訳し終わった片の訳を確定し、控えに残す。[ui] から呼ぶ。 */
        private suspend fun finish(finals: List<Pair<Int, String>>) {
            if (finals.isEmpty()) return
            val done = LinkedHashMap<String, String>()
            finals.forEach { (u, text) ->
                run.replace(u, text)
                done[run.units[u]] = text
            }
            cache.putAll(done)
            // 原文のまま返った訳（短い英語は訳し直さない）はデータベースに残さない。2Bが写した訳が控えに居座ると、
            // 0.8Bが2Bの訳を優先して、訳せる文まで原文のまま出し続ける（2026-11-12、「Sounds good」で起きた）
            persistent?.storeAll(done.filter { (source, output) -> !TranslationChecks.copied(source, output) })
        }
    }

    /**
     * 訳す片を、1回の生成に渡すまとまりに分ける。
     *
     * [parallel] が1なら、[MAX_LINES] 行・[MAX_CHARS] 字までを改行でつないで1回で訳す（1件ずつ順に訳すので、
     * 1回ごとにかかる原文の前置き（PFML3の見出し）の処理を減らすため）。行数がずれた・原文のまま写した行は訳し直す（[Jobs]）。
     *
     * 2以上なら、つながずに1片を1回で訳す（2026-11-12、作者の判断「並列でできるなら、並列の場合は訳し直しのロジックは
     * なしでいい」）。つなぐと、行数のずれや写しで訳し直しが毎回1〜4件出て（Discordでの試験）、その行はまとまり1回分＋1行分
     * 遅れた。また、まとまりの中の行は上から順に訳されるので、長い片の後ろの短い片が長い片の訳し終わりを待った
     * （作者の指摘「Discordでボットの長文の訳が終わるまで、次の短文の訳が始まらない」）。1片ずつなら訳し直しは起きず、
     * 長い片が1本の流れを使っている間も、ほかの流れで短い片が進む。前置きの処理は同時に訳す計算の中でまとめて済む。
     */
    private fun chunked(indices: List<Int>, texts: List<String>, parallel: Int): List<List<Int>> {
        if (parallel > 1) return indices.map { listOf(it) }
        val chunks = mutableListOf<MutableList<Int>>()
        var chars = 0
        for (i in indices) {
            val current = chunks.lastOrNull()
            if (current == null || current.size >= MAX_LINES || chars + texts[i].length > MAX_CHARS) {
                chunks += mutableListOf(i)
                chars = texts[i].length
            } else {
                current += i
                chars += texts[i].length
            }
        }
        return chunks
    }

    companion object {
        /**
         * [current] が同じエンジン・原文の言語・背景なら使い回し（訳の控えを生かす）、違えば作り直す。
         * モデルを切り替えるとエンジンが変わり、古いエンジンは閉じられて訳せないので、エンジンも必ず見る
         * （2026-11-06、見ずに使い回して画面翻訳・カメラ翻訳の訳が失敗し続けた）。
         */
        fun reuseOrCreate(
            current: BatchTranslator?,
            engine: TranslationEngine,
            target: Language,
            source: Language?,
            context: String? = null,
            persistent: TranslationCache.Scope? = null,
        ): BatchTranslator = current?.takeIf {
            it.engine === engine && it.target == target && it.source == source && it.context == context &&
                (it.persistent == null) == (persistent == null)
        } ?: BatchTranslator(engine, target, source, context, persistent)

        private const val TAG = "PlamoTranslate"
        private const val MAX_LINES = 8
        private const val MAX_CHARS = 400

        private const val CACHE_SIZE = 500
    }
}
