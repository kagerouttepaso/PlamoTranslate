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

    @Test
    fun ぶれたコマの読み取りは票が軽い() {
        // ぶれた2回が同じ崩れ方をしても、鋭い1回の読み取りが勝つ
        assertEquals("COLD BREW", ReadingFusion.fuse(listOf("COLD BREW", "C0LD BRCW", "C0LD BRCW"), 0, listOf(1.0, 0.2, 0.2)).text)
        val t = LineTracker.Track<Unit>(1)
        listOf("C0LD BRCW" to 1.0, "COLD BREW" to 10.0, "C0LD BRCW" to 1.5).forEach { (text, s) ->
            t.add(Reading(Box(0, 0, 100, 20), text, Unit, sharpness = s))
        }
        assertEquals("COLD BREW", t.consensus())
        assertTrue(t.lastBlurred())
    }

    @Test
    fun 鋭さの小さな揺れでは票を軽くしない() {
        val t = LineTracker.Track<Unit>(1)
        listOf(10.0, 8.0, 9.0).forEach { t.add(Reading(Box(0, 0, 100, 20), "COLD BREW", Unit, sharpness = it)) }
        assertEquals(listOf(1.0, 1.0, 1.0), t.weights())
        assertTrue(t.settled())
    }

    private fun track(vararg readings: Triple<String, Double?, Int>) = LineTracker.Track<Unit>(1).also { t ->
        readings.forEach { (text, sharp, height) -> t.add(Reading(Box(0, 0, 400, height), text, Unit, sharpness = sharp)) }
    }

    @Test
    fun 偶数回のときはいちばん軽い読み取りを除き鋭い読み取りは外れない() {
        // 古い順に、鋭い正しい読み取り、ぶれた崩れた読み取りが3回。偶数回でも、鋭い1回を除かない
        val t = track(
            Triple("COLD BREW", 10.0, 20), Triple("C0LD BRCW", 1.0, 20), Triple("C0LD BRCW", 1.5, 20),
        )
        assertEquals("COLD BREW", t.consensus())
        t.add(Reading(Box(0, 0, 400, 20), "COLD BRFW", Unit, sharpness = 1.2))
        assertEquals("COLD BREW", t.consensus())
    }

    @Test
    fun 鋭い1回の票だけではそろったとしない() {
        // 鋭い1回とぶれた2回が互いに一致しない。確かさは高く出るが、票の重みの合計が2回分に足りない
        val t = track(Triple("abcd", 10.0, 20), Triple("wxyz", 1.0, 20), Triple("pqrs", 1.0, 20))
        assertFalse(t.settled())
    }

    @Test
    fun 鋭さを測れなかった読み取りはほかと同じ重み() {
        val t = track(Triple("COLD BREW", 10.0, 20), Triple("COLD BREW", null, 20), Triple("COLD BREW", 9.0, 20))
        assertEquals(listOf(1.0, 1.0, 1.0), t.weights())
    }

    @Test
    fun 鋭さは同じくらいの大きさに写ったコマどうしでだけ比べる() {
        // 近づいて大きく写ったコマは鋭さの値が違っても、小さく写ったコマの基準にしない
        val t = track(Triple("COLD BREW", 3.0, 20), Triple("COLD BREW", 10.0, 60), Triple("COLD BREW", 2.8, 20))
        assertEquals(listOf(1.0, 1.0, 1.0), t.weights())
    }

    @Test
    fun 重みの小数の誤差で同数の決め方が変わらない() {
        // 0.1 + 0.2 と 0.3 は2進の小数では等しくないが、同数として土台の文字を採る
        assertEquals("abcd", ReadingFusion.fuse(listOf("abcd", "abcx", "abcd", "abcx"), 0, listOf(0.1, 0.3, 0.2, 1e-12)).text)
    }
}
