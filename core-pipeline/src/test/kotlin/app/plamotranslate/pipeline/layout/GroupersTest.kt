package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** まとめる工程（[LineGrouper]・[TextLayout]）の約束と、まとめ方ごとの規則。 */
class GroupersTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, kana: Boolean = false, latin: Boolean = false) =
        TextLine(text, Box(l, t, r, b), kana, latin)

    private fun groups(grouping: Grouping, lines: List<TextLine>): List<List<String>> =
        TextLayout.units(lines, grouping).map { u -> u.members.map { lines[it].text } }

    private fun randomLines(random: Random) = List(random.nextInt(1, 20)) { i ->
        val l = random.nextInt(0, 900)
        val t = random.nextInt(0, 1800)
        line("行$i", l, t, l + random.nextInt(10, 300), t + random.nextInt(10, 80))
    }

    // ---- どのまとめ方にも共通の約束 ----

    @Test
    fun どのまとめ方でもどの行もちょうど1つの単位に入る() {
        val random = Random(1)
        repeat(50) {
            val lines = randomLines(random)
            for (g in Grouping.entries) {
                assertEquals("$g", lines.indices.toList(), TextLayout.units(lines, g).flatMap { it.members }.sorted())
            }
        }
    }

    @Test
    fun 単位の位置は行の位置を合わせたもので_種類はまとめ方で決まる() {
        val lines = listOf(line("a", 0, 0, 100, 20), line("b", 0, 24, 80, 44))
        for (g in Grouping.entries) for (u in TextLayout.units(lines, g)) {
            assertEquals(u.members.map { lines[it].box }.reduce { a, b -> a.union(b) }, u.box)
            assertEquals(g.kind, u.kind)
        }
    }

    @Test
    fun 縦の列だけの単位はどのまとめ方でも縦書き() {
        val column = listOf(line("这是什么", 100, 0, 130, 200))
        for (g in Grouping.entries) assertTrue("$g", TextLayout.units(column, g).single().vertical)
        // 英字だけの縦長の行は横書き
        val narrow = listOf(line("HI", 100, 0, 130, 200, latin = true))
        for (g in Grouping.entries) assertFalse("$g", TextLayout.units(narrow, g).single().vertical)
    }

    @Test
    fun 同じ段の行は左から読む() {
        // 2026-11-06、英語の吹き出しで「HAVE FUN / I STILL」と逆転した（上端の数ピクセルの差）
        val lines = listOf(line("FUN", 110, 0, 190, 20, latin = true), line("HAVE", 0, 3, 100, 22, latin = true), line("I STILL", 0, 26, 190, 46, latin = true))
        assertEquals(listOf(listOf("HAVE", "FUN", "I STILL")), groups(Grouping.BUBBLE, lines))
    }

    // ---- 吹き出し ----

    @Test
    fun 吹き出し_上下に詰まった行はつなぎ上から読む() {
        val lines = listOf(line("下", 10, 30, 110, 50), line("上", 0, 0, 120, 20), line("遠い", 0, 300, 120, 320))
        assertEquals(listOf(listOf("上", "下"), listOf("遠い")), groups(Grouping.BUBBLE, lines).sortedByDescending { it.size })
    }

    @Test
    fun 吹き出し_行間が行の高さの8割以上ならつながない() {
        val lines = listOf(line("上", 0, 0, 120, 20), line("下", 0, 36, 120, 56))
        assertEquals(2, groups(Grouping.BUBBLE, lines).size)
    }

    @Test
    fun 吹き出し_幅の違う行も中心が近ければ同じ列とみなす() {
        // 中央そろえの吹き出し：左右が重ならなくても中心が近ければつなぐ。中心が遠ければつながない
        val apart = listOf(line("長い行です", 0, 0, 200, 20), line("短", 205, 24, 215, 44))
        assertEquals(2, groups(Grouping.BUBBLE, apart).size)
        val centered = listOf(line("長い行です", 0, 0, 200, 20), line("短い", 80, 24, 120, 44))
        assertEquals(1, groups(Grouping.BUBBLE, centered).size)
    }

    @Test
    fun 吹き出し_2割以上重なる行は形に関係なくつなぐ() {
        val lines = listOf(line("横", 0, 0, 200, 20), line("縦", 150, 0, 170, 60))
        assertEquals(1, groups(Grouping.BUBBLE, lines).size)
    }

    @Test
    fun 吹き出し_縦の列は左右に詰まればつなぎ右から読む() {
        val lines = listOf(line("左", 0, 0, 20, 100), line("右", 25, 0, 45, 100), line("遠い", 200, 0, 220, 100))
        val units = TextLayout.units(lines, Grouping.BUBBLE).sortedByDescending { it.members.size }
        assertEquals(2, units.size)
        assertTrue(units[0].vertical)
        assertEquals(listOf(1, 0), units[0].members)
    }

    @Test
    fun 吹き出し_英字の行は列としてつながない() {
        val lines = listOf(line("AB", 0, 0, 20, 40, latin = true), line("CD", 24, 0, 44, 40, latin = true))
        assertEquals(2, groups(Grouping.BUBBLE, lines).size)
    }

    @Test
    fun 吹き出し_仮名の行とほかの行はつながない() {
        val lines = listOf(line("これは", 0, 0, 120, 20, kana = true), line("这是", 0, 24, 120, 44))
        assertEquals(2, groups(Grouping.BUBBLE, lines).size)
    }

    @Test
    fun 吹き出し_横長の行を含むまとまりは縦書きにしない() {
        val lines = listOf(line("縦", 0, 0, 20, 100), line("横の行", 0, 0, 200, 20))
        assertFalse(TextLayout.units(lines, Grouping.BUBBLE).single().vertical)
    }

    // ---- 文章 ----

    @Test
    fun 文章_番号で始まる行は新しい段落にする() {
        val lines = listOf(line("注意", 0, 0, 200, 20), line("つづき", 0, 24, 200, 44), line("1. 項目", 0, 48, 200, 68))
        assertEquals(listOf(listOf("注意", "つづき"), listOf("1. 項目")), groups(Grouping.TEXT, lines))
    }

    @Test
    fun 文章_段落は6行まで() {
        val lines = List(8) { line("行$it", 0, it * 24, 200, it * 24 + 20) }
        assertEquals(listOf(6, 2), groups(Grouping.TEXT, lines).map { it.size })
    }

    @Test
    fun 文章_高さの比が16倍を超える行と横に重ならない行はつながない() {
        val close = listOf(line("見出し", 0, 0, 200, 30), line("本文", 0, 34, 200, 54))
        assertEquals(1, groups(Grouping.TEXT, close).size)
        val taller = listOf(line("見出し", 0, 0, 200, 40), line("本文", 0, 44, 200, 58))
        assertEquals(2, groups(Grouping.TEXT, taller).size)
        val apart = listOf(line("左", 0, 0, 100, 20), line("右", 150, 24, 250, 44))
        assertEquals(2, groups(Grouping.TEXT, apart).size)
    }

    // ---- メニュー ----

    @Test
    fun メニュー_1行ずつでボタンの中の2段書きだけまとめる() {
        val lines = listOf(
            line("바람", 0, 0, 60, 20), line("세기", 2, 22, 58, 42),   // ボタンの中の2段書き
            line("상점", 200, 0, 260, 20), line("업적", 200, 80, 260, 100),  // 間の空いた別の項目
        )
        assertEquals(listOf(listOf("바람", "세기"), listOf("상점"), listOf("업적")), groups(Grouping.MENU, lines))
    }

    @Test
    fun メニュー_中央がずれた行と幅の違いすぎる行はまとめない() {
        val shifted = listOf(line("바람", 0, 0, 60, 20), line("세기", 30, 22, 90, 42))
        assertEquals(2, groups(Grouping.MENU, shifted).size)
        val wide = listOf(line("바람", 100, 0, 130, 20), line("아주 긴 설명입니다", 30, 22, 200, 42))
        assertEquals(2, groups(Grouping.MENU, wide).size)
    }

    // ---- 並べて範囲を付ける ----

    @Test
    fun 並べて範囲を付ける_範囲は原文を含み_原文の重ならない単位どうしで重ならない() {
        val random = Random(3)
        val screen = Box(0, 0, 1080, 2400)
        repeat(50) {
            val lines = randomLines(random)
            for (g in Grouping.entries) {
                val placed = TextLayout.arrange(lines, g, screen)
                assertEquals(lines.indices.toList(), placed.flatMap { it.unit.members }.sorted())
                for (a in placed) {
                    assertTrue(a.room.contains(a.unit.box))
                    for (b in placed) if (a !== b && !a.unit.box.intersects(b.unit.box)) assertFalse(a.room.intersects(b.room))
                }
            }
        }
    }

    @Test
    fun 読み順は上から_同じ高さなら左から() {
        val units = listOf(
            TextUnit(UnitKind.BUBBLE, listOf(0), Box(300, 5, 400, 20), false),
            TextUnit(UnitKind.BUBBLE, listOf(1), Box(0, 100, 50, 120), false),
            TextUnit(UnitKind.BUBBLE, listOf(2), Box(0, 0, 50, 20), false),
        )
        assertEquals(listOf(2, 0, 1), ReadingOrder.sort(units).map { it.members.single() })
    }
}
