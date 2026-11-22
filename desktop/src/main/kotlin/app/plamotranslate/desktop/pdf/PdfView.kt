package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfPageSize

/*
 * PDF のビューアの表示の決まり（並べ方・拡大率・戻る/進む・検索の結果）。ブラウザー版が pdf.js に任せていた部分で、
 * 画面（Compose）に依存しない計算だけをここに置き、画面は結果を描くだけにする。
 */

/** ページの並べ方。ブラウザー版（pdf.js）の「1ページずつ・見開き・見開き（表紙を単独）」と同じ。 */
internal enum class PdfSpread(val label: String, val short: String) {
    SINGLE("1ページずつ", "1ページ"),
    ODD("見開き", "見開き"),
    COVER("見開き（表紙を単独）", "見開き・表紙");

    /** 行ごとのページ番号（0 始まり）。見開きは左から右へ並べる。 */
    fun rows(count: Int): List<List<Int>> = when {
        count <= 0 -> emptyList()
        this == SINGLE -> (0 until count).map { listOf(it) }
        this == ODD -> (0 until count).chunked(2)
        else -> listOf(listOf(0)) + (1 until count).chunked(2)
    }
}

/** 拡大率の指定。幅・ページ全体に合わせる指定は、ウィンドウの大きさが変わるたびに倍率を計算し直す。 */
internal sealed interface PdfZoom {
    val label: String

    data object FitWidth : PdfZoom { override val label = "幅に合わせる" }
    data object FitPage : PdfZoom { override val label = "ページ全体" }
    data class Scale(val percent: Int) : PdfZoom { override val label = "$percent%" }

    companion object {
        /** 拡大・縮小のボタンと Ctrl+ホイールで順に移る倍率（pdf.js の段階に近づけた）。 */
        val steps = listOf(25, 33, 50, 67, 75, 80, 90, 100, 110, 125, 150, 175, 200, 250, 300, 400)

        fun parse(value: String?): PdfZoom? = when (value) {
            "width" -> FitWidth
            "page" -> FitPage
            else -> value?.toIntOrNull()?.takeIf { it in steps.first()..steps.last() }?.let(::Scale)
        }

        fun key(zoom: PdfZoom): String = when (zoom) {
            FitWidth -> "width"
            FitPage -> "page"
            is Scale -> zoom.percent.toString()
        }
    }
}

/** 画面の大きさとページの寸法から、PDF の1ポイントを何 dp で描くかを決める。100% は 96dpi（1pt = 4/3dp）。 */
internal object PdfScale {
    const val POINT = 4f / 3f
    /** ページの間とページの外側の余白（dp）。 */
    const val GAP = 12f

    fun dpPerPoint(zoom: PdfZoom, sizes: List<PdfPageSize>, rows: List<List<Int>>, viewWidth: Float, viewHeight: Float): Float {
        if (sizes.isEmpty() || rows.isEmpty()) return POINT
        return when (zoom) {
            is PdfZoom.Scale -> zoom.percent / 100f * POINT
            PdfZoom.FitWidth -> fitWidth(sizes, rows, viewWidth)
            PdfZoom.FitPage -> minOf(fitWidth(sizes, rows, viewWidth), fitHeight(sizes, viewHeight))
        }.coerceIn(PdfZoom.steps.first() / 100f * POINT, PdfZoom.steps.last() / 100f * POINT)
    }

    /** 実際に描いている倍率を百分率にする（幅に合わせているときの表示と、拡大・縮小の起点に使う）。 */
    fun percent(dpPerPoint: Float): Int = Math.round(dpPerPoint / POINT * 100)

    /** [current] から1段階拡大（[direction] > 0）または縮小した倍率。 */
    fun step(current: Int, direction: Int): PdfZoom.Scale {
        val steps = PdfZoom.steps
        val next = if (direction > 0) steps.firstOrNull { it > current } ?: steps.last()
            else steps.lastOrNull { it < current } ?: steps.first()
        return PdfZoom.Scale(next)
    }

    private fun fitWidth(sizes: List<PdfPageSize>, rows: List<List<Int>>, viewWidth: Float): Float {
        val widest = rows.maxOf { row -> row.sumOf { sizes[it].width } }.toFloat()
        val gaps = rows.maxOf { it.size + 1 } * GAP
        return ((viewWidth - gaps) / widest).coerceAtLeast(0.01f)
    }

    private fun fitHeight(sizes: List<PdfPageSize>, viewHeight: Float): Float {
        val tallest = sizes.maxOf { it.height }.toFloat()
        return ((viewHeight - 2 * GAP) / tallest).coerceAtLeast(0.01f)
    }
}

/** 見出しやリンクで移動した位置の履歴。ブラウザーの戻る・進むと同じく、新しく移動すると進む先は消える。 */
internal data class PdfHistory(val back: List<Int> = emptyList(), val forward: List<Int> = emptyList()) {
    val canBack get() = back.isNotEmpty()
    val canForward get() = forward.isNotEmpty()

    /** [from] から別の位置へ移動した。同じページの中の移動は履歴に積まない。 */
    fun visit(from: Int, to: Int): PdfHistory =
        if (from == to) this else PdfHistory((back + from).takeLast(LIMIT), emptyList())

    /** 戻った先のページと新しい履歴。戻れなければ null。 */
    fun back(current: Int): Pair<Int, PdfHistory>? =
        back.lastOrNull()?.let { it to PdfHistory(back.dropLast(1), (forward + current).takeLast(LIMIT)) }

    fun forward(current: Int): Pair<Int, PdfHistory>? =
        forward.lastOrNull()?.let { it to PdfHistory((back + current).takeLast(LIMIT), forward.dropLast(1)) }

    private companion object {
        const val LIMIT = 100
    }
}

/** 文書の中で見つかった箇所。[boxes] はページの PDF ポイント座標。 */
internal data class PdfFound(val page: Int, val boxes: List<app.plamotranslate.pipeline.pdf.PdfBounds>)

/** 検索の進み具合と結果。ページを順に調べ、見つかった箇所を足していく。 */
internal data class PdfSearch(
    val query: String = "",
    val hits: List<PdfFound> = emptyList(),
    val current: Int = -1,
    val searchedPages: Int = 0,
    val done: Boolean = false,
) {
    val active get() = query.isNotEmpty()
    val selected get() = hits.getOrNull(current)

    /** [from] ページ以降で最初の箇所を選ぶ（検索を始めた位置の近くから見せる）。 */
    fun add(found: List<PdfFound>, from: Int): PdfSearch {
        val all = (hits + found).sortedWith(compareBy({ it.page }, { -(it.boxes.firstOrNull()?.top ?: 0.0) }))
        val keep = selected
        val index = when {
            keep != null -> all.indexOf(keep)
            else -> all.indexOfFirst { it.page >= from }.takeIf { it >= 0 } ?: if (all.isEmpty()) -1 else 0
        }
        return copy(hits = all, current = index)
    }

    fun move(direction: Int): PdfSearch =
        if (hits.isEmpty()) this else copy(current = Math.floorMod(current + direction.compareTo(0), hits.size))
}
