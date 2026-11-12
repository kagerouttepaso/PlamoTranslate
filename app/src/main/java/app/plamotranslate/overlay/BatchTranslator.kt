package app.plamotranslate.overlay

import android.util.Log
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.llm.TranslationEvent
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.flow.transformWhile

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
        val run = Run(texts, onDelta, onReplace)

        // 控え：メモリにないものをデータベースからまとめて引き、メモリへ移す
        val memoryHits = run.units.count { cache[it] != null }
        val fromDb = persistent?.lookup(run.units.filter { cache[it] == null })?.also { cache.putAll(it) }.orEmpty()
        val pending = mutableListOf<Int>()
        for (u in run.units.indices) {
            val cached = cache[run.units[u]]
            if (cached != null) run.replace(u, cached) else pending += u
        }
        for (chunk in chunked(pending, run.units)) {
            translateChunk(chunk, run)
        }
        Log.i(
            TAG,
            "まとめ訳し: ${texts.size}件（重複を除いて${run.units.size}片）、控え メモリ${memoryHits}・データベース${fromDb.size}、" +
                "訳した${pending.size}片（訳し直し${run.retried}、打ち切り${run.stopped}）",
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
            pieces = texts.map { t -> TranslationChecks.splitLong(t).map { p -> index.getOrPut(p) { index.size } } }
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

    private suspend fun translateChunk(chunk: List<Int>, run: Run) {
        val sources = chunk.map { run.units[it] }
        val output = generate(sources.joinToString("\n"), chunk.size, run) { line, part -> chunk.getOrNull(line)?.let { run.delta(it, part) } }
        val lines = output.trim().split('\n').map { it.trim() }
        val redo = mutableListOf<Int>()
        val done = LinkedHashMap<String, String>()
        if (lines.size == chunk.size) {
            chunk.forEachIndexed { k, u ->
                // 行数が合っても、原文のまま写した行は訳し直す（言語の混ざったまとまりで起きる）
                if (TranslationChecks.untranslated(sources[k], lines[k], target)) {
                    redo += u
                } else {
                    run.replace(u, lines[k])
                    done[sources[k]] = lines[k]
                }
            }
        } else {
            // 行がずれたら、このまとまりは1つずつ訳し直す
            redo += chunk
        }
        for (u in redo) {
            run.retried++
            run.replace(u, "")
            val single = generate(run.units[u], 1, run) { _, part -> run.delta(u, part) }.trim()
            run.replace(u, single)
            done[run.units[u]] = single
        }
        cache.putAll(done)
        // 原文のまま返った訳（短い英語は訳し直さない）はデータベースに残さない。2Bが写した訳が控えに居座ると、
        // 0.8Bが2Bの訳を優先して、訳せる文まで原文のまま出し続ける（2026-11-12、「Sounds good」で起きた）
        persistent?.storeAll(done.filter { (source, output) -> !TranslationChecks.copied(source, output) })
    }

    /**
     * [text]（[lines] 行）を訳し、訳文の全体を返す。訳文の続きは行番号付きで [onPart] へ渡す。
     * 止まらなくなった生成（[TranslationChecks.runaway]）は打ち切り、打ち切った所までを返す。
     */
    private suspend fun generate(text: String, lines: Int, run: Run, onPart: (line: Int, part: String) -> Unit): String {
        val output = StringBuilder()
        var line = 0
        var cut = 0
        engine.translate(TranslationRequest(text, source = source, target = target, context = context))
            .transformWhile { event ->
                if (event is TranslationEvent.Delta) {
                    output.append(event.text)
                    // 改行のたびに次の行へ移る
                    event.text.split('\n').forEachIndexed { k, part ->
                        if (k > 0) line++
                        if (part.isNotEmpty() && line < lines) onPart(line, part)
                    }
                    cut = TranslationChecks.runaway(output.toString(), lines, text.length)
                }
                emit(Unit)
                cut == 0
            }
            .collect { }
        if (cut > 0) {
            run.stopped++
            return output.substring(0, cut)
        }
        return output.toString()
    }

    private fun chunked(indices: List<Int>, texts: List<String>): List<List<Int>> {
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
