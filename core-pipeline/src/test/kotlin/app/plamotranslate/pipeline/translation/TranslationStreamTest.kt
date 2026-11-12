package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 翻訳の契約（[TranslationStream]）の振る舞い。偽のエンジン（[FakeEngine]）で、片への分け方・同時に訳す・訳し直し・控え・取り消しを確かめる。
 */
class TranslationStreamTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After
    fun tearDown() = scope.cancel()

    /**
     * 偽のエンジン。依頼の原文を行ごとに [translate] で訳し、1文字ずつ流す。最大 [slots] 本を1文字ずつ交互に進め、
     * 空いた流れにはすぐ次の依頼を入れる（本物の nativeTranslateJobs と同じ順番）。
     */
    private class FakeEngine(
        override val parallel: Int,
        val translate: (String) -> String = { "訳「$it」" },
    ) : JobEngine {
        val requests = mutableListOf<String>()

        /** 依頼を受け取るたびに呼ぶ（訳している途中に命令を出す試験で使う）。 */
        var onRequest: (Int) -> Unit = {}

        override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int {
            class Active(val id: Int, val output: String) {
                var pos = 0
            }
            val active = mutableListOf<Active>()
            var total = 0
            while (true) {
                while (active.size < slots) {
                    val (id, request) = source.next() ?: break
                    synchronized(requests) { requests += request.text }
                    onRequest(requests.size)
                    active += Active(id, outputFor(request))
                }
                if (active.isEmpty()) return total
                for (a in active.toList()) {
                    if (a.pos >= a.output.length) {
                        active.remove(a)
                        source.onFinished(a.id)
                        continue
                    }
                    total++
                    val stop = source.onText(a.id, a.output[a.pos++].toString())
                    if (stop) {
                        active.remove(a)
                        source.onFinished(a.id)
                    }
                }
            }
        }

        private fun outputFor(request: TranslationRequest) = request.text.split('\n').joinToString("\n") { translate(it) }
    }

    private fun stream(engine: FakeEngine, cache: MemoryCache = MemoryCache(), memory: TranslationMemory? = null) =
        TranslationStream(scope, { TranslationStream.Backend(engine, engine, memory) }, TranslationStream.Options(Language.EN, Language.JA), cache)

    /** [ids] の文が全部確定（または失敗）するまで結果を集める。 */
    private fun TranslationStream.collectUntilDone(ids: Set<Long>): List<SegmentUpdate> = runBlocking {
        val done = HashSet<Long>()
        withTimeout(5_000) {
            updates.transformWhile { u ->
                emit(u)
                if (u is SegmentUpdate.Final || u is SegmentUpdate.Failed) done += u.id
                !done.containsAll(ids)
            }.toList()
        }
    }

    private fun finals(updates: List<SegmentUpdate>) =
        updates.filterIsInstance<SegmentUpdate.Final>().associate { it.id to it.text }

    @Test
    fun 複数行の文は全部の行を訳し改行を残す() {
        // テキスト翻訳は入力の全体を1つの文として頼む。片は1行に限るので、行ごとに分けてから訳し、元の改行へ戻す
        for (parallel in listOf(1, 4)) {
            val engine = FakeEngine(parallel = parallel)
            val s = stream(engine)
            s.submit(listOf(Segment(1, "one\ntwo\n\nfour\r\nfive")))
            assertEquals("parallel=$parallel", "訳「one」\n訳「two」\n\n訳「four」\n訳「five」", finals(s.collectUntilDone(setOf(1)))[1])
            assertTrue("parallel=$parallel：片に改行を入れない", engine.requests.all { r -> r.split('\n').none { it.isBlank() } })
        }
    }

    @Test
    fun 長い行を分けて訳しても行の区切りは残る() {
        val engine = FakeEngine(parallel = 4)
        val s = stream(engine)
        val long = (1..20).joinToString(" ") { "Sentence number $it is here." }
        s.submit(listOf(Segment(1, "$long\nlast")))
        val text = finals(s.collectUntilDone(setOf(1)))[1].orEmpty()
        assertEquals(2, text.split('\n').size)
        assertTrue(text.endsWith("\n訳「last」"))
    }

    @Test
    fun 同時に訳すときは文ごとに1回ずつ訳す() {
        val engine = FakeEngine(parallel = 4)
        val s = stream(engine)
        s.submit(listOf(Segment(1, "Hello"), Segment(2, "Good night"), Segment(3, "See you")))
        val updates = s.collectUntilDone(setOf(1, 2, 3))
        assertEquals(mapOf(1L to "訳「Hello」", 2L to "訳「Good night」", 3L to "訳「See you」"), finals(updates))
        assertEquals(3, engine.requests.size)
        // 確定の前に途中の訳が流れる
        assertTrue(updates.any { it is SegmentUpdate.Partial && it.id == 1L })
    }

    @Test
    fun 一件ずつ訳すときは行をつないで1回で訳す() {
        val engine = FakeEngine(parallel = 1)
        val s = stream(engine)
        s.submit(listOf(Segment(1, "Hello"), Segment(2, "Good night"), Segment(3, "See you")))
        val updates = s.collectUntilDone(setOf(1, 2, 3))
        assertEquals(mapOf(1L to "訳「Hello」", 2L to "訳「Good night」", 3L to "訳「See you」"), finals(updates))
        assertEquals(listOf("Hello\nGood night\nSee you"), engine.requests)
    }

    @Test
    fun 行がずれたら一件ずつ訳し直す() {
        // つないだ依頼では2行を1行にまとめて返し、1行だけの依頼は正しく訳す
        var first = true
        val fake = FakeEngine(parallel = 1) { line -> "訳「$line」" }
        val merging = object : JobEngine {
            override val parallel = 1
            override suspend fun run(slots: Int, source: JobSource, maxTokens: Int): Int {
                val wrapped = object : JobSource by source {
                    override fun next(): Pair<Int, TranslationRequest>? = source.next()?.let { (id, r) ->
                        if (first && r.text.contains('\n')) {
                            first = false
                            id to r.copy(text = r.text.replace('\n', ' '))
                        } else id to r
                    }
                }
                return fake.run(slots, wrapped, maxTokens)
            }
        }
        val s = TranslationStream(scope, { TranslationStream.Backend(merging, merging) }, TranslationStream.Options(Language.EN, Language.JA))
        s.submit(listOf(Segment(1, "Hello"), Segment(2, "Bye")))
        val updates = s.collectUntilDone(setOf(1, 2))
        assertEquals(mapOf(1L to "訳「Hello」", 2L to "訳「Bye」"), finals(updates))
        // つないだ2片とも、1片ずつ訳し直した
        assertEquals(2, s.stats.retried)
        assertEquals(listOf("Hello Bye", "Hello", "Bye"), fake.requests)
    }

    @Test
    fun 同じ片は一回だけ訳す() {
        val engine = FakeEngine(parallel = 4)
        val s = stream(engine)
        s.submit(listOf(Segment(1, "OK"), Segment(2, "OK"), Segment(3, "Cancel")))
        val updates = s.collectUntilDone(setOf(1, 2, 3))
        assertEquals("訳「OK」", finals(updates)[2])
        assertEquals(2, engine.requests.size)
    }

    @Test
    fun 長い文は同時に訳すときだけ文で分けてつなぐ() {
        val long = "This is the first sentence of a long message. ".repeat(4) + "And this is the end of it."
        val parallel = FakeEngine(parallel = 4)
        val updates = stream(parallel).also { it.submit(listOf(Segment(1, long))) }.collectUntilDone(setOf(1))
        // 文の切れ目で分けた片ごとに依頼し、訳をつないで1つの文の訳にする
        val pieces = TranslationChecks.splitLong(long, TranslationChecks.PARALLEL_SPLIT)
        assertTrue(pieces.size > 1)
        assertEquals(pieces.size, parallel.requests.size)
        assertEquals(TranslationChecks.joinPieces(pieces.map { "訳「$it」" }), finals(updates).getValue(1))
        val sequential = FakeEngine(parallel = 1)
        stream(sequential).also { it.submit(listOf(Segment(1, long))) }.collectUntilDone(setOf(1))
        assertEquals(1, sequential.requests.size)
    }

    @Test
    fun メモリの控えは流れどうしで共有する() {
        val engine = FakeEngine(parallel = 4)
        val cache = MemoryCache()
        stream(engine, cache).also { it.submit(listOf(Segment(1, "Hello"))) }.collectUntilDone(setOf(1))
        val second = stream(engine, cache)
        second.submit(listOf(Segment(7, "Hello")))
        val updates = second.collectUntilDone(setOf(7))
        val final = updates.filterIsInstance<SegmentUpdate.Final>().single()
        assertEquals("訳「Hello」", final.text)
        assertTrue(final.cached)
        assertEquals(1, engine.requests.size)
    }

    @Test
    fun 長く残す控えを引き_写しは残さない() {
        val stored = HashMap<String, String>()
        val memory = object : TranslationMemory {
            override suspend fun lookup(texts: Collection<String>) = mapOf("Saved" to "保存済み").filterKeys { it in texts }
            override suspend fun store(translations: Map<String, String>) {
                stored.putAll(translations)
            }
        }
        // 「Sounds good」は原文のまま返す（写し）
        val engine = FakeEngine(parallel = 4) { if (it == "Sounds good") it else "訳「$it」" }
        val s = stream(engine, memory = memory)
        s.submit(listOf(Segment(1, "Saved"), Segment(2, "Hello"), Segment(3, "Sounds good")))
        val updates = s.collectUntilDone(setOf(1, 2, 3))
        assertEquals("保存済み", finals(updates)[1])
        assertEquals(listOf("Hello", "Sounds good"), engine.requests.sorted())
        runBlocking { withTimeout(2_000) { while (stored.isEmpty()) kotlinx.coroutines.delay(10) } }
        assertEquals(mapOf("Hello" to "訳「Hello」"), stored)
    }

    @Test
    fun 全部やめたら確定を流さない() = runBlocking {
        val engine = FakeEngine(parallel = 1)
        val s = stream(engine)
        engine.onRequest = { s.clear() }
        s.submit(listOf(Segment(1, "Hello")))
        // やめた後に頼んだ文だけが確定する
        kotlinx.coroutines.delay(200)
        engine.onRequest = {}
        s.submit(listOf(Segment(2, "Again")))
        val first = withTimeout(5_000) { s.updates.first { it is SegmentUpdate.Final } }
        assertEquals(2L, first.id)
    }

    @Test
    fun 一部をやめた文は訳さない() {
        // 1片ずつ訳すコア（同時に2本）で、1件目を訳し始めたところで、まだ並んでいる2件目をやめる
        val engine = FakeEngine(parallel = 2)
        val s = stream(engine)
        engine.onRequest = { n -> if (n == 1) s.cancel(listOf(2L)) }
        s.submit(listOf(Segment(1, "One")))
        s.submit(listOf(Segment(2, "Two"), Segment(3, "Three")))
        val updates = s.collectUntilDone(setOf(1, 3))
        assertEquals(setOf(1L, 3L), finals(updates).keys)
        assertTrue(engine.requests.none { it.contains("Two") })
    }

    @Test
    fun 訳している間に頼んだ文も続けて訳す() {
        val engine = FakeEngine(parallel = 2)
        val s = stream(engine)
        engine.onRequest = { n -> if (n == 1) s.submit(listOf(Segment(9, "Late"))) }
        s.submit(listOf(Segment(1, "Early")))
        val updates = s.collectUntilDone(setOf(1, 9))
        assertEquals(mapOf(1L to "訳「Early」", 9L to "訳「Late」"), finals(updates))
    }

    @Test
    fun 翻訳モデルが無ければ失敗を流す() {
        val s = TranslationStream(scope, { null }, TranslationStream.Options(Language.EN, Language.JA))
        s.submit(listOf(Segment(1, "Hello")))
        val updates = s.collectUntilDone(setOf(1))
        assertTrue(updates.single() is SegmentUpdate.Failed)
    }
}
