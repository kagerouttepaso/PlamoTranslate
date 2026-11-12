package app.plamotranslate.pipeline.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 開始の判定A（[StartJudge]）と有効性の判定B（[ValidityJudge]）。作った格子の並びで判断を確かめる。 */
class JudgesTest {
    /** 480×480 の画面を 48×48 の格子にしたもの。[fill] でます目ごとの明るさを決める。 */
    private fun grid(fill: (col: Int, row: Int) -> Int): Thumb {
        val n = ScreenChange.COLS
        return Thumb(480, 480, n, n, IntArray(n * n) { fill(it % n, it / n) })
    }

    /** ます目（10px）で長方形を作る。 */
    private fun cells(c0: Int, r0: Int, c1: Int, r1: Int) = Box(c0 * 10, r0 * 10, c1 * 10, r1 * 10)

    @Test
    fun 判定A_止まった画面が続けば訳し始める() {
        val judge = StartJudge(SceneProfile.NORMAL.start)
        val still = grid { _, _ -> 100 }
        judge.reset(0)
        assertFalse(judge.feed(still, 0, null))
        assertFalse(judge.feed(still, 300, null))
        assertTrue(judge.feed(null, 700, null))
    }

    @Test
    fun 判定A_動き続ける間は訳さない() {
        val judge = StartJudge(SceneProfile.NORMAL.start)
        judge.reset(0)
        for (t in 0..1000 step 200) assertFalse(judge.feed(grid { c, r -> if ((c + r + t / 200) % 2 == 0) 0 else 200 }, t.toLong(), null))
    }

    @Test
    fun 判定A_動く画面は背景が動き続けても待ちの上限で訳す() {
        val judge = StartJudge(SceneProfile.MOTION.start)
        judge.reset(0)
        var ready = false
        for (t in 0..3200 step 200) ready = judge.feed(grid { c, r -> if ((c + r + t / 200) % 2 == 0) 0 else 200 }, t.toLong(), null)
        assertTrue(ready)
    }

    /** 背景（明るさ50）に、訳す原文の吹き出し（ます目10〜20、明るさ250）がある画面。 */
    private val reference = grid { c, r -> if (c in 10..19 && r in 10..19) 250 else 50 }
    private val bubble = cells(12, 12, 18, 18)

    @Test
    fun 判定B_原文の周りが変わらなければ有効() {
        val judge = ValidityJudge(SceneProfile.NORMAL.validity)
        judge.begin(reference)
        // 訳の枠の中（自分の表示）は描き変わっても見ない
        val now = grid { c, r -> if (c in 12..17 && r in 12..17) 0 else if (c in 10..19 && r in 10..19) 250 else 50 }
        assertEquals(Validity.Valid, judge.feed(now, listOf(bubble), listOf(bubble), watchOutside = true))
    }

    @Test
    fun 判定B_別の画面に移ったら無効() {
        val judge = ValidityJudge(SceneProfile.NORMAL.validity)
        judge.begin(reference)
        val other = grid { c, r -> if (r % 3 == 0) 230 else 20 }
        assertTrue(judge.feed(other, listOf(bubble), listOf(bubble), watchOutside = true) is Validity.Invalid)
    }

    @Test
    fun 判定B_動く画面で背景が動いても原文の周りが同じなら有効() {
        // 2026-11-12までは、動いたます目を外した結果、画面の8割を見ずに判定していた。ここでは外周だけを見る
        val judge = ValidityJudge(SceneProfile.MOTION.validity)
        judge.begin(reference)
        for (t in 0 until 6) {
            val moving = grid { c, r -> if (c in 10..19 && r in 10..19) 250 else if ((c + t * 3) % 7 < 3 && r > 30) 200 else 50 }
            assertEquals("t=$t", Validity.Valid, judge.feed(moving, listOf(bubble), listOf(bubble), watchOutside = true))
        }
    }

    @Test
    fun 判定B_動く画面でも原文の場所が変われば無効() {
        // 戻ってチャンネル一覧へ移ったような、原文の周りが別のものになった画面（背景の動きの除外で見逃さない）
        val judge = ValidityJudge(SceneProfile.MOTION.validity)
        judge.begin(reference)
        val moved = grid { c, r -> if (c in 25..35 && r in 10..19) 250 else if (r % 4 == 0) 120 else 50 }
        var verdict: Validity = Validity.Valid
        repeat(3) { verdict = judge.feed(moved, listOf(bubble), listOf(bubble), watchOutside = true) }
        assertTrue(verdict is Validity.Invalid)
    }

    @Test
    fun 判定B_のぞいて枠の下が変われば無効() {
        val judge = ValidityJudge(SceneProfile.MOTION.validity)
        judge.begin(reference)
        assertEquals(Validity.Valid, judge.peek(reference, listOf(bubble)))
        val nextLine = grid { c, r -> if (c in 12..17 && r in 12..17 && (c + r) % 2 == 0) 0 else if (c in 10..19 && r in 10..19) 250 else 50 }
        assertTrue(judge.peek(nextLine, listOf(bubble)) is Validity.Invalid)
    }

    @Test
    fun 外周は箱のすぐ外だけ() {
        val ring = ScreenChange.ring(reference, listOf(cells(10, 10, 12, 12)), margin = 1)
        val marked = ring.indices.filter { ring[it] }.map { it % ScreenChange.COLS to it / ScreenChange.COLS }.toSet()
        // 箱は 10〜11 のます目、外周は 9 と 12 の帯
        assertEquals(12, marked.size)
        assertTrue((9 to 9) in marked && (12 to 12) in marked)
        assertFalse((10 to 10) in marked)
    }
}
