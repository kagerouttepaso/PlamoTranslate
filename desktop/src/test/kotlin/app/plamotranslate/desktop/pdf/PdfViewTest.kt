package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfPageSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfViewTest {
    @Test
    fun `見開きは奇数始まりと表紙を単独にする並べ方を選べる`() {
        assertEquals(listOf(listOf(0), listOf(1), listOf(2)), PdfSpread.SINGLE.rows(3))
        assertEquals(listOf(listOf(0, 1), listOf(2, 3), listOf(4)), PdfSpread.ODD.rows(5))
        assertEquals(listOf(listOf(0), listOf(1, 2), listOf(3, 4)), PdfSpread.COVER.rows(5))
        assertTrue(PdfSpread.COVER.rows(0).isEmpty())
    }

    @Test
    fun `幅に合わせると一番広い行が余白を残して収まりページ全体は高さでも収める`() {
        val sizes = listOf(PdfPageSize(600.0, 800.0), PdfPageSize(600.0, 800.0))
        val single = PdfSpread.SINGLE.rows(2)
        val width = PdfScale.dpPerPoint(PdfZoom.FitWidth, sizes, single, 624f, 400f)
        assertEquals(1f, width, 0.001f)
        val spread = PdfScale.dpPerPoint(PdfZoom.FitWidth, sizes, PdfSpread.ODD.rows(2), 1236f, 400f)
        assertEquals(1f, spread, 0.001f)
        val page = PdfScale.dpPerPoint(PdfZoom.FitPage, sizes, single, 624f, 424f)
        assertEquals(0.5f, page, 0.001f)
        assertEquals(100, PdfScale.percent(PdfScale.dpPerPoint(PdfZoom.Scale(100), sizes, single, 1f, 1f)))
    }

    @Test
    fun `拡大と縮小は今の倍率から段階の次へ移る`() {
        assertEquals(PdfZoom.Scale(110), PdfScale.step(100, 1))
        assertEquals(PdfZoom.Scale(90), PdfScale.step(100, -1))
        assertEquals(PdfZoom.Scale(125), PdfScale.step(118, 1))
        assertEquals(PdfZoom.Scale(400), PdfScale.step(400, 1))
        assertEquals(PdfZoom.Scale(25), PdfScale.step(25, -1))
    }

    @Test
    fun `拡大率の指定は保存した文字列から戻せる`() {
        listOf(PdfZoom.FitWidth, PdfZoom.FitPage, PdfZoom.Scale(150)).forEach {
            assertEquals(it, PdfZoom.parse(PdfZoom.key(it)))
        }
        assertNull(PdfZoom.parse("9999"))
    }

    @Test
    fun `戻る進むはブラウザーと同じく新しく移動すると進む先を消す`() {
        var history = PdfHistory().visit(0, 10).visit(10, 20)
        val (back, afterBack) = checkNotNull(history.back(20))
        assertEquals(10, back)
        assertTrue(afterBack.canForward)
        val (forward, afterForward) = checkNotNull(afterBack.forward(10))
        assertEquals(20, forward)
        history = afterBack.visit(10, 5)
        assertFalse(history.canForward)
        assertEquals(listOf(0, 10), history.back)
        assertEquals(history, history.visit(5, 5))
    }

    @Test
    fun `検索は始めた位置以降の最初の箇所を選び次と前を巡回する`() {
        val box = PdfBounds(0.0, 0.0, 1.0, 1.0)
        var search = PdfSearch(query = "a").add(listOf(PdfFound(1, listOf(box)), PdfFound(5, listOf(box))), from = 3)
        assertEquals(5, search.selected?.page)
        search = search.add(listOf(PdfFound(3, listOf(box))), from = 3)
        assertEquals(5, search.selected?.page)
        assertEquals(1, search.move(1).selected?.page)
        assertEquals(3, search.move(-1).selected?.page)
    }
}
