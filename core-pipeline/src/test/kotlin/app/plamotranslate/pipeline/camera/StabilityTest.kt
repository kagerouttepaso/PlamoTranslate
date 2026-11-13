package app.plamotranslate.pipeline.camera

import app.plamotranslate.pipeline.screen.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 区切りの多数決（[StableGrouping]）と、文字ごとの多数決（[ReadingFusion]）。 */
class StabilityTest {
    private fun fuse(vararg readings: String): ReadingFusion.Fused {
        val list = readings.toList()
        // 土台は「ほかといちばん似ている」ものだが、ここでは最初のものを土台にして多数決だけを確かめる
        return ReadingFusion.fuse(list, 0)
    }

    @Test
    fun 崩れた読み取りが混じっても文字ごとの多数決で正しい文になる() {
        // どの1回の読み取りも1文字ずつ崩れているが、位置ごとには正しい文字が多い
        assertEquals("COLD BREW", fuse("C0LD BREW", "COLD BRCW", "COLD BREW", "CQLD BREW").text)
        assertEquals("COLD BREW", fuse("COLD BRCW", "C0LD BREW", "COLD 8REW").text)
    }

    @Test
    fun 抜けと挟まりも多数決で直す() {
        // 1回だけ文字が抜けた・1回だけ余計な文字が挟まった
        assertEquals("오야꼬동", fuse("오야꼬동", "오야동", "오야꼬동").text)
        assertEquals("ramen", fuse("ramen", "rameen", "ramen").text)
        // 過半数が挟んだ文字は採る
        assertEquals("ramen", fuse("rmen", "ramen", "ramen").text)
    }

    @Test
    fun 読み取りがそろうほど確かさが上がる() {
        val shaky = fuse("C0LD BREW", "COLD BRCW", "CQLD 8REW").confidence
        val stable = fuse("COLD BREW", "COLD BREW", "COLD BREW").confidence
        assertEquals(1.0, stable, 1e-9)
        assertTrue(shaky < stable)
    }

    @Test
    fun 同数に割れても土台の文字を採り読み取りの順で結果が変わらない() {
        // 2対2に割れた位置は土台の文字を採る。土台が同じなら、ほかの読み取りの順を入れ替えても同じ文になる
        assertEquals("abcd", ReadingFusion.fuse(listOf("abcd", "abcx", "abcx", "abcd"), 0).text)
        assertEquals("abcd", ReadingFusion.fuse(listOf("abcd", "abcd", "abcx", "abcx"), 0).text)
    }

    @Test
    fun 互いに一致しない読み取りでも文字ごとの多数決が確かならそろったとみなす() {
        // どの2回もほぼ同じ文ではない（短い語の1文字違い）が、位置ごとには多数が決まる
        val t = LineTracker.Track<Unit>(1)
        listOf("abcd", "xbcd", "axcd").forEach { t.add(Reading(Box(0, 0, 100, 20), it, Unit)) }
        assertEquals("abcd", t.consensus())
        assertTrue(t.agreement() < LineTracker.AGREE)
        assertTrue(t.settled())
    }

    @Test
    fun 読み取りが2回までは文字ごとの多数決を使わない() {
        val t = LineTracker.Track<Unit>(1)
        listOf("abcd", "xbcd").forEach { t.add(Reading(Box(0, 0, 100, 20), it, Unit)) }
        assertFalse(t.settled())
    }

    private val both = setOf(1L, 2L)

    @Test
    fun 境目の行が1から2コマ揺れても段落は変わらない() {
        val g = StableGrouping()
        // 2行はつながっている
        repeat(3) { g.observe(listOf(listOf(1L, 2L)), both, both) }
        assertEquals(listOf(listOf(1L, 2L)), g.groups(listOf(1L, 2L)))
        // 2コマだけ離れて見えた
        repeat(2) { g.observe(listOf(listOf(1L), listOf(2L)), both, both) }
        assertEquals(listOf(listOf(1L, 2L)), g.groups(listOf(1L, 2L)))
    }

    @Test
    fun 離れていた行が1から2コマつながって見えても段落は変わらない() {
        val g = StableGrouping()
        repeat(3) { g.observe(listOf(listOf(1L), listOf(2L)), both, both) }
        repeat(2) { g.observe(listOf(listOf(1L, 2L)), both, both) }
        assertEquals(listOf(listOf(1L), listOf(2L)), g.groups(listOf(1L, 2L)))
    }

    @Test
    fun はっきり離れたら切り替える() {
        val g = StableGrouping()
        repeat(3) { g.observe(listOf(listOf(1L, 2L)), both, both) }
        repeat(4) { g.observe(listOf(listOf(1L), listOf(2L)), both, both) }
        assertEquals(listOf(listOf(1L), listOf(2L)), g.groups(listOf(1L, 2L)))
        // 離れた後も、1コマつながって見えただけでは戻らない
        g.observe(listOf(listOf(1L, 2L)), both, both)
        assertEquals(listOf(listOf(1L), listOf(2L)), g.groups(listOf(1L, 2L)))
    }

    @Test
    fun 切り替えた段落はそのコマのまとめ方の段落になる() {
        // 区切りの位置がずれ続けたとき、前の段落と新しい段落をつなぎ合わせず、そのコマのまとめ方の段落に置き換える
        // （つなぎ合わせると、まとめ方の行数の上限や表・メニューの2行までの約束を超える段落ができる）
        val g = StableGrouping()
        val all = setOf(1L, 2L, 3L, 4L)
        repeat(3) { g.observe(listOf(listOf(1L, 2L), listOf(3L, 4L)), all, all) }
        repeat(6) { g.observe(listOf(listOf(1L), listOf(2L, 3L), listOf(4L)), all, all) }
        assertEquals(setOf(listOf(1L), listOf(2L, 3L), listOf(4L)), g.groups(listOf(1L, 2L, 3L, 4L)).toSet())
    }

    @Test
    fun 段落が伸びたら新しい行はすぐ段落に入る() {
        val g = StableGrouping()
        repeat(3) { g.observe(listOf(listOf(1L, 2L)), both, both) }
        val three = setOf(1L, 2L, 3L)
        g.observe(listOf(listOf(1L, 2L, 3L)), three, three)
        assertEquals(listOf(listOf(1L, 2L, 3L)), g.groups(listOf(1L, 2L, 3L)))
    }

    @Test
    fun 隠れている間も追跡が続く行の区切りは覚えている() {
        val g = StableGrouping()
        repeat(3) { g.observe(listOf(listOf(1L, 2L)), both, both) }
        // 行2が1コマ隠れた（追跡は続いている）。見えている行1だけでは、区切りが違ったとは数えない
        repeat(4) { g.observe(listOf(listOf(1L)), setOf(1L), both) }
        assertEquals(listOf(listOf(1L, 2L)), g.groups(listOf(1L, 2L)))
    }

    @Test
    fun 追跡が切れた行の区切りは忘れる() {
        val g = StableGrouping()
        g.observe(listOf(listOf(1L, 2L)), both, both)
        g.observe(listOf(listOf(3L)), setOf(3L), setOf(3L))
        // 行1と2が区切りに無い行として戻ってきても、前の区切りは覚えていない
        assertEquals(listOf(listOf(1L), listOf(2L)), g.groups(listOf(1L, 2L)))
    }
}
