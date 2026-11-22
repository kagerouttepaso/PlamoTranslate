package app.plamotranslate.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.HorizontalScrollbar
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plamotranslate.desktop.pdf.PdfBookmark
import app.plamotranslate.desktop.pdf.PdfDestination
import app.plamotranslate.desktop.pdf.PdfFound
import app.plamotranslate.desktop.pdf.PdfHistory
import app.plamotranslate.desktop.pdf.PdfLink
import app.plamotranslate.desktop.pdf.PdfPoint
import app.plamotranslate.desktop.pdf.PdfScale
import app.plamotranslate.desktop.pdf.PdfSearch
import app.plamotranslate.desktop.pdf.PdfViewport
import app.plamotranslate.desktop.pdf.PdfZoom
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfPageSize
import app.plamotranslate.pipeline.pdf.PdfState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign
import kotlin.math.sqrt

/*
 * PDF のビューアのページ部分。ブラウザー版の pdf.js が受け持っていた、連続スクロール・拡大縮小・見開き・リンク・
 * 文字の選択・検索の強調を、PDFium の文書（PdfDocument）から描く。訳を書き込んだページは最新の版の文書から描き、
 * 原文/訳文の切り替えは描く版を変えるだけにする（上に訳を重ねない）。訳している最中の段落だけ、背景色を半透明で重ねて
 * 生成途中の訳を見せる（ブラウザー版と同じ）。
 */

private data class RenderKey(val version: Long, val page: Int, val width: Int)
private data class LinkKey(val version: Long, val page: Int)

/** 選択した文字。[boxes] はページの PDF ポイント座標。 */
internal data class PdfTextSelection(val page: Int, val version: Long, val text: String, val boxes: List<PdfBounds>)

/** 1つの文書を開いている間のビューアの状態。文書を開き直すと作り直す。 */
@Stable
internal class PdfViewerModel(private val scope: CoroutineScope, private val openExternal: (String) -> Unit) {
    var sizes by mutableStateOf<List<PdfPageSize>>(emptyList())
        private set
    var viewports by mutableStateOf<Map<Int, PdfViewport>>(emptyMap())
        private set
    var outline by mutableStateOf<List<PdfBookmark>>(emptyList())
        private set
    var history by mutableStateOf(PdfHistory())
        private set
    var search by mutableStateOf(PdfSearch())
        private set
    var searchOpen by mutableStateOf(false)
    var selection by mutableStateOf<PdfTextSelection?>(null)
        private set
    var currentPage by mutableStateOf(0)
        internal set

    val listState = LazyListState()
    internal var rows: List<List<Int>> = emptyList()
    internal var pixelsPerPoint = 1f
    /** 今描いている倍率（PDF の1ポイントあたりの dp）。拡大率の表示と、拡大・縮小の起点に使う。 */
    var dpPerPoint by mutableStateOf(PdfScale.POINT)
        internal set

    private val links = mutableStateMapOf<LinkKey, List<PdfLink>>()
    private val images = object : LinkedHashMap<RenderKey, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<RenderKey, ImageBitmap>) = size > IMAGE_CACHE
    }
    private var selecting: Job? = null
    private var searching: Job? = null

    /** ページの寸法・座標の向き・見出しを原文の文書から読む。 */
    suspend fun load(original: PdfViewVersion) {
        val lease = original.acquire() ?: return
        try {
            val document = lease.document
            val count = original.pageCount
            val pageSizes = ArrayList<PdfPageSize>(count)
            val pageViewports = HashMap<Int, PdfViewport>(count)
            for (page in 0 until count) {
                pageSizes += document.pageSize(page)
                pageViewports[page] = document.viewport(page)
            }
            viewports = pageViewports
            sizes = pageSizes
            outline = try { document.outline() } catch (error: Exception) {
                if (error is CancellationException) throw error
                emptyList()
            }
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Logs.w("PDF のページ情報を読めません", error)
        } finally {
            lease.close()
        }
    }

    fun cached(version: Long, page: Int, width: Int): ImageBitmap? = images[RenderKey(version, page, width)]

    /** 同じページの別の倍率・別の版の画像。新しい画像ができるまでの仮の表示に使う。 */
    fun fallback(page: Int): ImageBitmap? = images.entries.lastOrNull { it.key.page == page }?.value

    suspend fun render(version: PdfViewVersion, page: Int, width: Int, height: Int): ImageBitmap? {
        val key = RenderKey(version.number, page, width)
        images[key]?.let { return it }
        val lease = version.acquire() ?: return null
        val pixels = try {
            lease.document.render(page, width, height)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            Logs.w("PDF のページを描けません", error)
            return null
        } finally {
            lease.close()
        }
        val bitmap = withContext(Dispatchers.Default) {
            BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB).apply {
                setRGB(0, 0, width, height, pixels, 0, width)
            }.toComposeImageBitmap()
        }
        images[key] = bitmap
        return bitmap
    }

    suspend fun loadLinks(version: PdfViewVersion, page: Int) {
        val key = LinkKey(version.number, page)
        if (key in links) return
        val lease = version.acquire() ?: return
        links[key] = try {
            lease.document.links(page)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            emptyList()
        } finally {
            lease.close()
        }
    }

    fun linkAt(version: Long, page: Int, point: PdfPoint): PdfLink? =
        links[LinkKey(version, page)]?.firstOrNull { link -> link.hitAreas.any { it.contains(point) } }

    /** 見出し・リンクの行き先へ移動する。ページ内の移動は戻る・進むの履歴に積む。 */
    fun follow(destination: PdfDestination?) {
        when (destination) {
            is PdfDestination.Page -> goTo(destination.index, destination.y)
            is PdfDestination.Uri -> openExternal(destination.uri)
            null -> Unit
        }
    }

    fun goTo(page: Int, top: Double? = null, remember: Boolean = true) {
        if (page !in sizes.indices) return
        if (remember) history = history.visit(currentPage, page)
        scope.launch { scroll(page, top, centered = false) }
    }

    fun back() {
        val (page, next) = history.back(currentPage) ?: return
        history = next
        scope.launch { scroll(page, null, centered = false) }
    }

    fun forward() {
        val (page, next) = history.forward(currentPage) ?: return
        history = next
        scope.launch { scroll(page, null, centered = false) }
    }

    /** ドラッグの始点と終点の文字の間を選ぶ。始点・終点が文字の上でなければ、近い文字を探す。 */
    fun select(version: PdfViewVersion, page: Int, from: PdfPoint, to: PdfPoint) {
        selecting?.cancel()
        selecting = scope.launch {
            val lease = version.acquire() ?: return@launch
            try {
                val document = lease.document
                // 行の外の余白から・余白までドラッグしても選べるよう、文字の上でなければ範囲を広げて近い文字を探す。
                suspend fun near(point: PdfPoint) = document.characterAt(page, point.x, point.y, SELECT_TOLERANCE)
                    ?: document.characterAt(page, point.x, point.y, SELECT_REACH)
                val start = near(from) ?: return@launch
                val end = near(to) ?: return@launch
                val chosen = document.selection(page, minOf(start, end), abs(end - start) + 1)
                selection = PdfTextSelection(page, version.number, chosen.text, chosen.boxes)
            } catch (error: Exception) {
                if (error is CancellationException) throw error
            } finally {
                lease.close()
            }
        }
    }

    fun clearSelection() {
        selecting?.cancel()
        selection = null
    }

    /** 表示中のページから順に、表示している版（原文または訳文）の文字を探す。 */
    fun find(query: String, versionFor: (Int) -> PdfViewVersion?) {
        searching?.cancel()
        val from = currentPage
        search = PdfSearch(query = query)
        if (query.isBlank()) return
        searching = scope.launch {
            val count = sizes.size
            for (step in 0 until count) {
                val page = (from + step) % count
                val version = versionFor(page) ?: continue
                val lease = version.acquire() ?: continue
                val hits = try {
                    lease.document.search(page, query)
                } catch (error: Exception) {
                    if (error is CancellationException) throw error
                    emptyList()
                } finally {
                    lease.close()
                }
                val hadSelection = search.selected != null
                search = search.add(hits.map { PdfFound(page, it.boxes) }, from).copy(searchedPages = step + 1)
                if (!hadSelection && search.selected != null) reveal()
            }
            search = search.copy(done = true)
        }
    }

    fun moveSearch(direction: Int) {
        search = search.move(direction)
        reveal()
    }

    fun closeSearch() {
        searching?.cancel()
        searchOpen = false
        search = PdfSearch()
    }

    private fun reveal() {
        val found = search.selected ?: return
        scope.launch { scroll(found.page, found.boxes.maxOfOrNull { it.top }, centered = true) }
    }

    private suspend fun scroll(page: Int, top: Double?, centered: Boolean) {
        val row = rows.indexOfFirst { page in it }
        if (row < 0) return
        val size = sizes.getOrNull(page) ?: return
        val y = top?.let { viewports[page]?.toView(0.0, it)?.y } ?: 0.0
        val margin = if (centered) listState.layoutInfo.viewportSize.height / 3 else 0
        val offset = (y * size.height * pixelsPerPoint).roundToInt() - margin
        if (offset >= 0) listState.scrollToItem(row, offset) else {
            listState.scrollToItem(row)
            listState.scrollBy(offset.toFloat())
        }
    }

    private suspend fun LazyListState.scrollBy(value: Float) = this.scroll { scrollBy(value) }

    private companion object {
        const val IMAGE_CACHE = 24
        const val SELECT_TOLERANCE = 6.0
        const val SELECT_REACH = 36.0
    }
}

private fun PdfBounds.contains(point: PdfPoint) = point.x in left..right && point.y in bottom..top

/** ページを連続して並べて描く。拡大率は [zoom] と画面の大きさから決める。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PdfPages(
    model: PdfViewerModel,
    state: PdfState,
    documents: PdfDocuments,
    view: PdfViewState,
    setZoom: (PdfZoom) -> Unit,
    pageChanged: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val sizes = model.sizes
    val settings = view.settings
    val rows = remember(settings.spread, sizes.size) { settings.spread.rows(sizes.size) }
    model.rows = rows
    val density = LocalDensity.current.density
    BoxWithConstraints(modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest)) {
        val viewWidth = maxWidth.value - SCROLLBAR
        val viewHeight = maxHeight.value
        val scale = PdfScale.dpPerPoint(settings.zoom, sizes, rows, viewWidth, viewHeight)
        model.pixelsPerPoint = scale * density
        SideEffect { model.dpPerPoint = scale }
        val contentWidth = max(viewWidth, rows.maxOfOrNull { row ->
            row.sumOf { sizes[it].width }.toFloat() * scale + (row.size + 1) * PdfScale.GAP
        } ?: viewWidth)

        // 倍率が変わっても、見ていた位置を保つ。
        var shownScale by remember(model) { mutableStateOf(scale) }
        LaunchedEffect(scale) {
            if (shownScale != scale && rows.isNotEmpty()) {
                val ratio = scale / shownScale
                val list = model.listState
                list.scrollToItem(list.firstVisibleItemIndex, (list.firstVisibleItemScrollOffset * ratio).roundToInt())
            }
            shownScale = scale
        }
        // 並べ方を変えたら、見ていたページへ戻る。
        var shownSpread by remember(model) { mutableStateOf(settings.spread) }
        LaunchedEffect(settings.spread) {
            if (shownSpread != settings.spread) model.goTo(model.currentPage, remember = false)
            shownSpread = settings.spread
        }
        // 表示の中心にある行のページを、訳す順番の起点として知らせる。
        LaunchedEffect(model) {
            snapshotFlow {
                val info = model.listState.layoutInfo
                val center = (info.viewportStartOffset + info.viewportEndOffset) / 2
                (info.visibleItemsInfo.firstOrNull { it.offset <= center && it.offset + it.size > center }
                    ?: info.visibleItemsInfo.firstOrNull())?.index
            }.filterNotNull().distinctUntilChanged().collect { row ->
                val page = model.rows.getOrNull(row)?.first() ?: return@collect
                model.currentPage = page
                pageChanged(page)
            }
        }

        val horizontal = rememberScrollState()
        Box(
            Modifier.fillMaxSize().horizontalScroll(horizontal).onPointerEvent(PointerEventType.Scroll, PointerEventPass.Initial) { event ->
                if (!event.keyboardModifiers.isCtrlPressed) return@onPointerEvent
                val delta = event.changes.firstOrNull()?.scrollDelta?.y ?: return@onPointerEvent
                if (delta != 0f) setZoom(PdfScale.step(PdfScale.percent(scale), -delta.sign.toInt()))
                event.changes.forEach { it.consume() }
            },
        ) {
            LazyColumn(
                state = model.listState,
                modifier = Modifier.width(contentWidth.dp).fillMaxHeight(),
                contentPadding = PaddingValues(vertical = (PdfScale.GAP / 2).dp),
            ) {
                items(rows.size, key = { rows[it].first() }) { index ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = (PdfScale.GAP / 2).dp),
                        horizontalArrangement = Arrangement.spacedBy(PdfScale.GAP.dp, Alignment.CenterHorizontally),
                    ) {
                        rows[index].forEach { page ->
                            val version = documents.versionFor(page, view.showTranslation) ?: return@forEach
                            PdfPage(model, page, version, sizes[page], scale, state, view.showTranslation)
                        }
                    }
                }
            }
        }
        // 既定のスクロールバーは黒の半透明で、暗い背景では見えないので、画面の文字の色で太めに描く。
        val onSurface = MaterialTheme.colorScheme.onSurface
        val scrollbar = remember(onSurface) {
            ScrollbarStyle(
                minimalHeight = 32.dp, thickness = 10.dp, shape = RoundedCornerShape(5.dp), hoverDurationMillis = 200,
                unhoverColor = onSurface.copy(alpha = 0.35f), hoverColor = onSurface.copy(alpha = 0.6f),
            )
        }
        CompositionLocalProvider(LocalScrollbarStyle provides scrollbar) {
            VerticalScrollbar(rememberScrollbarAdapter(model.listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().padding(2.dp))
            if (contentWidth > viewWidth + 1) {
                HorizontalScrollbar(rememberScrollbarAdapter(horizontal), Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(2.dp))
            }
        }
    }
}

/** 訳を書き込んで最新の版に入ったページは訳文の版、それ以外は原文の版で描く。 */
internal fun PdfDocuments.versionFor(page: Int, showTranslation: Boolean): PdfViewVersion? =
    if (showTranslation && page in writtenPages) latest ?: original else original

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun PdfPage(
    model: PdfViewerModel,
    page: Int,
    version: PdfViewVersion,
    pageSize: PdfPageSize,
    scale: Float,
    state: PdfState,
    showTranslation: Boolean,
) {
    val density = LocalDensity.current.density
    val widthPx = pageSize.width * scale * density
    val heightPx = pageSize.height * scale * density
    // 大きく拡大しても1枚の画像が大きくなりすぎないよう、描く画素の数を抑える（画面には引き伸ばして出す）。
    val limit = minOf(1.0, MAX_SIDE / max(widthPx, heightPx).toDouble(), sqrt(MAX_PIXELS / (widthPx.toDouble() * heightPx)))
    val pixelsWide = (widthPx * limit).roundToInt().coerceAtLeast(1)
    val pixelsHigh = (heightPx * limit).roundToInt().coerceAtLeast(1)
    var image by remember(page) { mutableStateOf(model.fallback(page)) }
    LaunchedEffect(version.number, page, pixelsWide) {
        model.cached(version.number, page, pixelsWide)?.let { image = it; return@LaunchedEffect }
        // スクロールや拡大の途中で通り過ぎるページは描かない。
        delay(RENDER_DELAY_MS)
        model.render(version, page, pixelsWide, pixelsHigh)?.let { image = it }
    }
    LaunchedEffect(version.number, page) { model.loadLinks(version, page) }

    val viewport = model.viewports[page]
    var overLink by remember { mutableStateOf(false) }
    val measurer = rememberTextMeasurer()
    val selection = model.selection?.takeIf { it.page == page && it.version == version.number }
    val hits = model.search.hits.filter { it.page == page }
    val selectedHit = model.search.selected?.takeIf { it.page == page }
    val working = if (showTranslation) state.layouts[page].orEmpty().filter { paragraph ->
        paragraph.text in state.working && paragraph.elements.none { it.original != null }
    } else emptyList()
    val highlight = MaterialTheme.colorScheme.primary

    Box(
        Modifier.size((pageSize.width * scale).dp, (pageSize.height * scale).dp).shadow(2.dp).background(Color.White)
            .pointerHoverIcon(if (overLink) PointerIcon.Hand else PointerIcon.Text)
            .onPointerEvent(PointerEventType.Move) { event ->
                val position = event.changes.firstOrNull()?.position ?: return@onPointerEvent
                overLink = viewport != null && model.linkAt(version.number, page, viewport.point(position, size.width, size.height)) != null
            }
            .onPointerEvent(PointerEventType.Exit) { overLink = false }
            .pointerInput(version.number, page, viewport) {
                val pageViewport = viewport ?: return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    if (!currentEvent.buttons.isPrimaryPressed) return@awaitEachGesture
                    val start = pageViewport.point(down.position, size.width, size.height)
                    val link = model.linkAt(version.number, page, start)
                    var moved = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if ((change.position - down.position).getDistance() > viewConfiguration.touchSlop) moved = true
                        if (moved && link == null) {
                            model.select(version, page, start, pageViewport.point(change.position, size.width, size.height))
                            change.consume()
                        }
                    }
                    if (!moved) {
                        if (link != null) model.follow(link.destination) else model.clearSelection()
                    }
                }
            },
    ) {
        image?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
        if (viewport != null) {
            Canvas(Modifier.fillMaxSize()) {
                working.forEach { paragraph ->
                    val box = rect(viewport, paragraph.bounds)
                    drawRect(Color.White.copy(alpha = WORKING_ALPHA), box.topLeft, box.size)
                    val preview = state.previews[paragraph.text]?.text
                    if (!preview.isNullOrEmpty() && box.width > 4 && box.height > 4) {
                        val fontPx = (paragraph.size * scale * density).toFloat().coerceIn(6f, box.height)
                        drawText(
                            measurer, preview, box.topLeft,
                            TextStyle(color = Color(paragraph.color.red, paragraph.color.green, paragraph.color.blue),
                                fontSize = (fontPx / density / fontScale * PREVIEW_SCALE).let { androidx.compose.ui.unit.TextUnit(it, androidx.compose.ui.unit.TextUnitType.Sp) }),
                            overflow = TextOverflow.Clip, size = box.size,
                        )
                    }
                }
                hits.forEach { hit ->
                    val color = if (hit == selectedHit) Color(0xFFFF9800).copy(alpha = 0.5f) else Color(0xFFFFEB3B).copy(alpha = 0.45f)
                    hit.boxes.forEach { drawBox(viewport, it, color) }
                }
                selection?.boxes?.forEach { drawBox(viewport, it, highlight.copy(alpha = 0.3f)) }
            }
        }
    }
}

private fun PdfViewport.point(position: Offset, width: Int, height: Int): PdfPoint =
    toPage((position.x / width).toDouble(), (position.y / height).toDouble())

private fun DrawScope.rect(viewport: PdfViewport, bounds: PdfBounds): Rect {
    val box = viewport.toView(bounds)
    return Rect(box.left * size.width, box.top * size.height, box.right * size.width, box.bottom * size.height)
}

private fun DrawScope.drawBox(viewport: PdfViewport, bounds: PdfBounds, color: Color) {
    val box = rect(viewport, bounds)
    drawRect(color, box.topLeft, Size(box.width, box.height))
}

private const val SCROLLBAR = 12f
private const val MAX_SIDE = 4096.0
private const val MAX_PIXELS = 12_000_000.0
private const val RENDER_DELAY_MS = 60L
/** ブラウザー版と同じく、訳している段落の原文を背景色で半分だけ隠す。 */
private const val WORKING_ALPHA = 0.45f
/** 生成途中の訳は原文より長くなりやすいので、原文の文字より少し小さく描く。 */
private const val PREVIEW_SCALE = 0.85f
