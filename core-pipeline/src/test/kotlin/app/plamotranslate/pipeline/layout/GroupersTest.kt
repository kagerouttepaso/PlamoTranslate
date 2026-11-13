package app.plamotranslate.pipeline.layout

import app.plamotranslate.pipeline.screen.Box
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** まとめる工程（[LineGrouper]）の約束と、まとめ方ごとの規則。 */
class GroupersTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int, kana: Boolean = false, latin: Boolean = false) =
        TextLine(text, Box(l, t, r, b), kana, latin)

    private fun groups(grouping: Grouping, lines: List<TextLine>): List<List<String>> =
        grouping.grouper.group(lines).map { u -> u.members.map { lines[it].text } }

    @Test
    fun どのまとめ方でもどの行もちょうど1つの単位に入る() {
        val random = Random(1)
        repeat(50) {
            val lines = List(random.nextInt(1, 20)) { i ->
                val l = random.nextInt(0, 900)
                val t = random.nextInt(0, 1800)
                line("行$i", l, t, l + random.nextInt(10, 300), t + random.nextInt(10, 80))
            }
            for (g in Grouping.entries) {
                val members = g.grouper.group(lines).flatMap { it.members }
                assertEquals("$g", lines.indices.toList(), members.sorted())
            }
        }
    }

    @Test
    fun 単位の位置は行の位置を合わせたもの() {
        val lines = listOf(line("a", 0, 0, 100, 20), line("b", 0, 24, 80, 44))
        for (g in Grouping.entries) for (u in g.grouper.group(lines)) {
            assertEquals(u.members.map { lines[it].box }.reduce { a, b -> a.union(b) }, u.box)
        }
    }

    @Test
    fun 吹き出し_上下に詰まった行はつなぎ上から読む() {
        val lines = listOf(line("下", 10, 30, 110, 50), line("上", 0, 0, 120, 20), line("遠い", 0, 300, 120, 320))
        assertEquals(listOf(listOf("上", "下"), listOf("遠い")), groups(Grouping.BUBBLE, lines).sortedBy { it.size }.reversed())
    }

    @Test
    fun 吹き出し_仮名の行とほかの行はつながない() {
        val lines = listOf(line("これは", 0, 0, 120, 20, kana = true), line("这是", 0, 24, 120, 44))
        assertEquals(2, groups(Grouping.BUBBLE, lines).size)
    }

    @Test
    fun 吹き出し_縦書きの列は右から読む() {
        val lines = listOf(line("左", 0, 0, 20, 100), line("右", 25, 0, 45, 100))
        val units = Grouping.BUBBLE.grouper.group(lines)
        assertEquals(1, units.size)
        assertTrue(units[0].vertical)
        assertEquals(listOf(1, 0), units[0].members)
    }

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
    fun メニュー_1行ずつでボタンの中の2段書きだけまとめる() {
        val lines = listOf(
            line("바람", 0, 0, 60, 20), line("세기", 2, 22, 58, 42),   // ボタンの中の2段書き
            line("상점", 200, 0, 260, 20), line("업적", 200, 80, 260, 100),  // 間の空いた別の項目
        )
        assertEquals(listOf(listOf("바람", "세기"), listOf("상점"), listOf("업적")), groups(Grouping.MENU, lines))
        assertTrue(Grouping.MENU.grouper.group(lines).all { it.kind == UnitKind.MENU_ITEM })
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
