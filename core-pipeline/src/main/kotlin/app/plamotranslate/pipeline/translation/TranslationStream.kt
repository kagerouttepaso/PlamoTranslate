package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.JapaneseForm
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 翻訳の契約（docs/設計/翻訳コア.md「翻訳の契約」）。テキスト・会話・画面・カメラの翻訳は、
 * すべてこの流れを通る。
 *
 * - 命令：[submit]（文を頼む）・[cancel]（一部の文をやめる）・[clear]（全部やめる）・[close]（流れを閉じる）。どれも返り値を持たず、すぐ戻る。
 * - 購読：[updates] で文ごとの途中の訳・確定の訳・失敗を受け取る（購読するのは1か所だけ）。
 *
 * 文は、エンジンの「同時に訳す数」に合うコア（[coreFor]）で片に分けて訳し、片の訳をつないで文の訳に戻す。同じ片は1回だけ訳す。
 * 控えは2層で、鍵はどちらも「範囲（[Options.cacheScope]）＋片の原文」（docs/設計/翻訳コア.md「控え」）。
 * メモリの控え（[cache]。流れどうしで共有できる）を引き、外れた片は渡されていれば永続の控え（[Backend.persistent]）を引く。
 * 訳している間に頼まれた文も、空いた流れですぐ訳す（永続の控えを引くのは、訳し始める前に頼まれた文だけ）。
 *
 * 状態は [lock] で守る。命令はどのスレッドから呼んでもよい。エンジンからの呼び出し（[source]）は推論のスレッドで来る。
 */
class TranslationStream(
    scope: CoroutineScope,
    private val backend: suspend () -> Backend?,
    val options: Options,
    private val cache: MemoryCache = MemoryCache(),
) {
    data class Options(
        val source: Language?,
        val target: Language,
        val form: JapaneseForm = JapaneseForm.POLITE,
        /** 原文の背景（PFML3の context）。カメラの表・メニューのモードで、短い語の意味を決める手がかりに渡す。 */
        val context: String? = null,
        /** 1回の依頼で生成するトークンの上限。 */
        val maxTokens: Int = DEFAULT_MAX_TOKENS,
    ) {
        /** 訳の控えの範囲。訳を変える条件だけを入れる（[maxTokens] は入れない）。 */
        val cacheScope: CacheScope get() = CacheScope(source, target, form, context)
    }

    /** 訳すのに使う部品。[origin] は訳すモデルで、控えはその確かさ以上の訳だけを使う。[persistent] は永続の控え（無ければ使わない）。 */
    class Backend(val engine: JobEngine, val origin: ModelOrigin, val persistent: PersistentCache? = null)

    /** 訳した結果の数（ログ用）。 */
    data class Stats(val pieces: Int, val memoryHits: Int, val storedHits: Int, val retried: Int, val stopped: Int)

    private val out = Channel<SegmentUpdate>(Channel.UNLIMITED)

    /** 文ごとの結果。購読するのは1か所だけにする。 */
    val updates: Flow<SegmentUpdate> = out.receiveAsFlow()

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** [clear] のたびに増やす。古い番号の依頼の結果は捨てる。 */
    private var generation = 0

    private class Pending(val segment: Segment, val at: Long)

    /**
     * 頼まれた文。[lines] は元の行ごとの片（空の行は片が0個）。片は改行を含まない。
     * 依頼の行と訳の行を対応させる（逐次のコアのつなぎ方、打ち切りの行数、途中の訳の行送り）ので、改行は片の中に入れず、ここで行に分けて持つ。
     */
    private class Seg(val lines: List<List<String>>, val at: Long) {
        val pieces = lines.flatten()
        var fromEngine = false
    }

    private class Job(val pieces: List<Piece>, val generation: Int) {
        val output = StringBuilder()
        var line = 0
        var cut = 0
        var tokens = 0
    }

    private val inbox = ArrayDeque<Pending>()
    private val segments = LinkedHashMap<Long, Seg>()

    /** 片の原文 → それを待っている文。 */
    private val waiting = HashMap<String, MutableSet<Long>>()

    /** 片の原文 → 訳している途中の訳。 */
    private val current = HashMap<String, StringBuilder>()

    /** 片の原文 → 確定した訳（今の [generation] の間）。 */
    private val finals = HashMap<String, String>()
    private val pieceTokens = HashMap<String, Int>()
    private val queue = ArrayDeque<Piece>()

    /** 並べた片（訳し終えるまで）。同じ片を2回並べないため。 */
    private val queued = HashSet<String>()
    private val jobs = HashMap<Int, Job>()
    private var nextJobId = 0
    private val tooLong = ArrayList<String>()
    private val toStore = LinkedHashMap<String, String>()
    private var core: TranslationCore = SequentialCore()
    private val cacheScope = options.cacheScope

    /** 今の実行の部品（[work] が実行のたびに入れ替える）。 */
    private var origin = ModelOrigin("", 0)
    private var persistent: PersistentCache? = null

    private var piecesTranslated = 0
    private var memoryHits = 0
    private var storedHits = 0
    private var retried = 0
    private var stopped = 0

    private val worker = scope.launch { work() }

    /** 文を頼む。結果は [updates] で届く。 */
    fun submit(segments: List<Segment>) {
        if (segments.isEmpty()) return
        val now = System.nanoTime()
        synchronized(lock) { segments.forEach { inbox.addLast(Pending(it, now)) } }
        wake.trySend(Unit)
    }

    /** [ids] の文をやめる。ほかの文が待っていない片は、まだ訳していなければ並びから外す。 */
    fun cancel(ids: Collection<Long>) = synchronized(lock) {
        val set = ids.toHashSet()
        inbox.removeAll { it.segment.id in set }
        for (id in set) {
            val seg = segments.remove(id) ?: continue
            for (p in seg.pieces.distinct()) {
                val w = waiting[p] ?: continue
                w.remove(id)
                if (w.isEmpty()) {
                    waiting.remove(p)
                    if (p !in finals) {
                        queue.removeAll { it.text == p }
                        queued.remove(p)
                    }
                }
            }
        }
    }

    /** 頼んだ文を全部やめる。訳している途中の依頼は次のトークンで止まり、その結果は捨てる。 */
    fun clear() = synchronized(lock) {
        generation++
        inbox.clear()
        segments.clear()
        waiting.clear()
        current.clear()
        finals.clear()
        pieceTokens.clear()
        queue.clear()
        queued.clear()
        tooLong.clear()
    }

    /** 流れを閉じる。訳している途中の依頼も止める。訳し終えた片は、永続の控えに書いてから止まる。 */
    fun close() {
        clear()
        worker.cancel()
        out.close()
    }

    val stats: Stats get() = synchronized(lock) { Stats(piecesTranslated, memoryHits, storedHits, retried, stopped) }

    private fun emit(update: SegmentUpdate) {
        out.trySend(update)
    }

    private suspend fun work() {
        for (signal in wake) {
            while (true) {
                if (synchronized(lock) { inbox.isEmpty() && queue.isEmpty() }) break
                val b = try {
                    backend()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (b == null) {
                    fail("翻訳モデルを読めません")
                    break
                }
                // 永続の控えを消していたら（設定の画面）、メモリの控えも捨てる。書くときは、訳し始める前のこの世代を渡す
                val stored = b.persistent?.let { it to it.generation }
                cache.sync(stored?.second ?: 0)
                val gen: Int
                val lookup = synchronized(lock) {
                    core = coreFor(b.engine.parallel)
                    origin = b.origin
                    persistent = b.persistent
                    gen = generation
                    drainInbox()
                }
                val hits = if (lookup.isNotEmpty() && b.persistent != null) {
                    try {
                        b.persistent.lookup(cacheScope, b.origin.quality, lookup)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        emptyMap()
                    }
                } else emptyMap()
                synchronized(lock) { if (gen == generation) enqueue(lookup, hits) }
                val c = synchronized(lock) { core.takeIf { queue.isNotEmpty() } }
                if (c != null) {
                    try {
                        b.engine.run(c.slots, source, options.maxTokens)
                        // 同時に訳すと流れ1本分のコンテキストに入らなかった片は、1本でコンテキストを全部使って訳す
                        if (c.slots > 1 && synchronized(lock) { requeueTooLong() }) b.engine.run(1, source, options.maxTokens)
                        synchronized(lock) { giveUpTooLong() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        fail(e.message ?: e.toString())
                    } finally {
                        // 確定はエンジンが戻る前に流れるので、購読する側は最後の確定を受け取ってすぐ閉じてよい。
                        // 閉じられても（取り消されても）、訳し終えた片は永続の控えに書き終える
                        withContext(NonCancellable) { flushStore(stored, b.origin) }
                    }
                }
            }
        }
    }

    /** 頼まれた文を片に分け、控え（メモリ）で決まる片を確定し、永続の控えを引く片を返す。[lock] の中で呼ぶ。 */
    private fun drainInbox(): List<String> {
        val lookup = LinkedHashSet<String>()
        while (inbox.isNotEmpty()) {
            val pending = inbox.removeFirst()
            val id = pending.segment.id
            if (pending.segment.text.isBlank()) {
                emit(SegmentUpdate.Final(id, "", cached = true))
                continue
            }
            // 行に分けてから片にする（片は改行を含まない。テキスト翻訳は複数行の入力を1つの文として頼む）
            val seg = Seg(pending.segment.text.lines().map { if (it.isBlank()) emptyList() else TranslationChecks.splitLong(it, core.splitAt) }, pending.at)
            segments[id] = seg
            for (p in seg.pieces.distinct()) {
                waiting.getOrPut(p) { LinkedHashSet() }.add(id)
                when {
                    p in finals || p in queued -> Unit
                    else -> {
                        val hit = cache.get(cacheScope, p, origin.quality)
                        if (hit != null) {
                            memoryHits++
                            finals[p] = hit
                        } else {
                            queued += p
                            lookup += p
                        }
                    }
                }
            }
            completeIfDone(id)
        }
        return lookup.toList()
    }

    /** 永続の控えを引いた結果で、片を確定するか並べる。[lock] の中で呼ぶ。 */
    private fun enqueue(texts: List<String>, hits: Map<String, String>) {
        for (p in texts) {
            if (waiting[p] == null) {
                // 引いている間にやめた
                queued.remove(p)
                continue
            }
            val hit = hits[p]
            if (hit != null) {
                storedHits++
                // 永続の控えの訳は今のモデル以上の確かさだが、正確な値は持たないので今のモデルの確かさで入れる
                cache.put(cacheScope, p, hit, origin.quality)
                pieceFinal(p, hit, fromEngine = false)
            } else {
                queue.addLast(Piece(p))
            }
        }
    }

    /** 片 [p] の訳が確定した。待っている文へ知らせる。[lock] の中で呼ぶ。 */
    private fun pieceFinal(p: String, text: String, fromEngine: Boolean) {
        finals[p] = text
        current[p] = StringBuilder(text)
        queued.remove(p)
        val ids = waiting[p]?.toList() ?: return
        for (id in ids) {
            val seg = segments[id] ?: continue
            if (fromEngine) seg.fromEngine = true
            if (!completeIfDone(id)) emit(SegmentUpdate.Partial(id, joined(seg)))
        }
    }

    /** 文 [id] の片が全部確定していれば、確定の訳を流して片付ける。[lock] の中で呼ぶ。 */
    private fun completeIfDone(id: Long): Boolean {
        val seg = segments[id] ?: return false
        if (!seg.pieces.all { it in finals }) return false
        emit(
            SegmentUpdate.Final(
                id,
                assemble(seg) { finals.getValue(it) },
                cached = !seg.fromEngine,
                millis = (System.nanoTime() - seg.at) / 1_000_000,
                tokens = seg.pieces.distinct().sumOf { pieceTokens[it] ?: 0 },
            ),
        )
        segments.remove(id)
        for (p in seg.pieces.distinct()) {
            val w = waiting[p] ?: continue
            w.remove(id)
            if (w.isEmpty()) waiting.remove(p)
        }
        return true
    }

    private fun joined(seg: Seg): String = assemble(seg) { finals[it] ?: current[it]?.toString().orEmpty() }

    /** 片の訳を、行の中では [TranslationChecks.joinPieces] でつなぎ、行どうしは元の改行でつなぐ。 */
    private fun assemble(seg: Seg, textOf: (String) -> String): String =
        seg.lines.joinToString("\n") { line -> TranslationChecks.joinPieces(line.map(textOf)) }

    private fun partialFor(p: String) {
        for (id in waiting[p].orEmpty()) segments[id]?.let { emit(SegmentUpdate.Partial(id, joined(it))) }
    }

    /** 流れ1本分に入らなかった片を並べ直す。並べたものがあれば true。[lock] の中で呼ぶ。 */
    private fun requeueTooLong(): Boolean {
        if (tooLong.isEmpty()) return false
        tooLong.filter { waiting[it] != null }.forEach { queue.addLast(Piece(it, single = true)) }
        tooLong.clear()
        return queue.isNotEmpty()
    }

    /** コンテキストを全部使っても入らなかった片は、空の訳にする。[lock] の中で呼ぶ。 */
    private fun giveUpTooLong() {
        val rest = tooLong.toList()
        tooLong.clear()
        rest.forEach { pieceFinal(it, "", fromEngine = true) }
    }

    /** 頼まれていた文を全部失敗にする。 */
    private fun fail(message: String) = synchronized(lock) {
        (segments.keys + inbox.map { it.segment.id }).distinct().forEach { emit(SegmentUpdate.Failed(it, message)) }
        generation++
        inbox.clear()
        segments.clear()
        waiting.clear()
        current.clear()
        queue.clear()
        queued.clear()
        tooLong.clear()
    }

    /** 実行で訳し終えた片を、永続の控え [stored]（控えと、訳し始める前の世代）へまとめて書く。 */
    private suspend fun flushStore(stored: Pair<PersistentCache, Int>?, origin: ModelOrigin) {
        val batch = synchronized(lock) {
            if (toStore.isEmpty()) return
            LinkedHashMap(toStore).also { toStore.clear() }
        }
        val (persistent, generation) = stored ?: return
        try {
            persistent.store(cacheScope, origin, generation, batch)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // 控えに残せなくても訳は出ている
        }
    }

    /** エンジンへ依頼を出す口。推論のスレッドから呼ばれる。 */
    private val source = object : JobSource {
        override fun next(): Pair<Int, TranslationRequest>? = synchronized(lock) {
            // 訳している間に頼まれた文：メモリの控えだけ見て並べる
            if (inbox.isNotEmpty()) enqueue(drainInbox(), emptyMap())
            if (queue.isEmpty()) return null
            val pieces = core.plan(queue)
            val id = nextJobId++
            jobs[id] = Job(pieces, generation)
            pieces.forEach { current[it.text] = StringBuilder() }
            id to TranslationRequest(pieces.joinToString("\n") { it.text }, options.source, options.target, options.form, options.context)
        }

        override fun onText(id: Int, text: String): Boolean = synchronized(lock) {
            val job = jobs[id] ?: return true
            if (job.generation != generation || job.cut > 0) return true
            job.output.append(text)
            job.tokens++
            // 改行のたびに次の行（片）へ移る
            text.split('\n').forEachIndexed { k, part ->
                if (k > 0) job.line++
                if (part.isNotEmpty() && job.line < job.pieces.size) {
                    val p = job.pieces[job.line].text
                    if (waiting[p] != null && p !in finals) {
                        current.getOrPut(p) { StringBuilder() }.append(part)
                        partialFor(p)
                    }
                }
            }
            val sourceChars = job.pieces.sumOf { it.text.length } + job.pieces.size - 1
            job.cut = TranslationChecks.runaway(job.output.toString(), job.pieces.size, sourceChars)
            if (job.cut > 0) stopped++
            job.cut > 0
        }

        override fun onFinished(id: Int) = synchronized(lock) {
            val job = jobs.remove(id) ?: return
            if (job.generation != generation) return
            val output = if (job.cut > 0) job.output.substring(0, job.cut) else job.output.toString()
            val outcome = core.finish(job.pieces, output, options.target)
            job.pieces.forEach { pieceTokens[it.text] = (pieceTokens[it.text] ?: 0) + job.tokens / job.pieces.size }
            for (p in outcome.retry.asReversed()) {
                if (waiting[p] == null) {
                    queued.remove(p)
                    continue
                }
                retried++
                current[p] = StringBuilder()
                partialFor(p)
                queue.addFirst(Piece(p, single = true))
            }
            for ((p, t) in outcome.finals) {
                piecesTranslated++
                cache.put(cacheScope, p, t, origin.quality)
                if (persistent != null && t.isNotEmpty() && !TranslationChecks.copied(p, t)) toStore[p] = t
                pieceFinal(p, t, fromEngine = true)
            }
        }

        override fun onTooLong(id: Int) = synchronized(lock) {
            val job = jobs.remove(id) ?: return
            if (job.generation == generation) tooLong += job.pieces.map { it.text }
        }
    }

    companion object {
        const val DEFAULT_MAX_TOKENS = 1024
    }
}

/**
 * 1つの文だけを訳す便利な関数（テキスト翻訳・会話翻訳）。命令（submit）と購読（updates）の上に薄く作ったもので、
 * 別の入口ではない。途中の訳を [onPartial] に渡し、確定（[SegmentUpdate.Final]）か失敗（[SegmentUpdate.Failed]）を返す。
 * 流れの [TranslationStream.updates] を購読するので、同じ流れをほかで購読しないこと。
 */
suspend fun TranslationStream.translateOne(text: String, onPartial: (String) -> Unit = {}): SegmentUpdate {
    val id = 1L
    submit(listOf(Segment(id, text)))
    var result: SegmentUpdate? = null
    updates.transformWhile { u ->
        if (u.id == id) {
            when (u) {
                is SegmentUpdate.Partial -> onPartial(u.text)
                is SegmentUpdate.Final, is SegmentUpdate.Failed -> result = u
            }
        }
        emit(u)
        result == null
    }.collect { }
    return result ?: SegmentUpdate.Failed(id, "翻訳が終わりませんでした")
}
