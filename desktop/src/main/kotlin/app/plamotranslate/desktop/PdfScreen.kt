package app.plamotranslate.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.pdf.PdfParagraph
import app.plamotranslate.pipeline.pdf.PdfRect
import app.plamotranslate.pipeline.pdf.PdfSession
import app.plamotranslate.pipeline.pdf.PdfState

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PdfScreen(state: PdfState<PdfPageImage>, presenter: PdfPresenter) {
    val dropTarget = remember(presenter) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.dragData() as? DragData.FilesList ?: return false
                presenter.drop(files.readFiles())
                return true
            }
        }
    }
    Column(Modifier.fillMaxSize().dragAndDropTarget({ it.dragData() is DragData.FilesList }, dropTarget),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = presenter::choose) { Text("PDF を開く") }
            LanguageMenu("訳す先: ${state.target.label}", Language.entries, presenter::selectTarget)
            OutlinedButton(onClick = presenter::switchView, enabled = state.pageCount > 0) {
                Text(if (state.sideBySide) "重ねて表示" else "並べて表示")
            }
        }
        if (state.pageCount == 0) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow), contentAlignment = Alignment.Center) {
                Text(when {
                    state.openFailed -> "PDF を開けませんでした。ファイルの破損やパスワードの有無を確認してください。"
                    state.opening -> "PDF を開いています…"
                    else -> "ここに PDF をドロップするか、「PDF を開く」を押してください。"
                })
            }
            return@Column
        }
        PdfPageControls(state, presenter)
        PdfPageContent(state, Modifier.weight(1f))
        val status = when {
            state.page in state.unreadable -> "このページの文字を読み取れませんでした。"
            state.layouts[state.page]?.isEmpty() == true -> "このページには取り出せる文字がありません。"
            state.untranslated[state.page]?.isNotEmpty() == true -> "一部を翻訳できませんでした。翻訳モデルを確認してください。"
            state.translationPage == state.page -> "翻訳中…"
            else -> null
        }
        if (status != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(status, style = MaterialTheme.typography.bodySmall)
                if (state.page in state.untranslated) TextButton(onClick = presenter::retry) { Text("もう一度訳す") }
            }
        }
    }
}

@Composable
private fun PdfPageControls(state: PdfState<PdfPageImage>, presenter: PdfPresenter) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { presenter.page(state.page - 1) }, enabled = state.page > 0) { Text("前へ") }
        Text("${state.page + 1} / ${state.pageCount}")
        TextButton(onClick = { presenter.page(state.page + 1) }, enabled = state.page < state.pageCount - 1) { Text("次へ") }
        TextButton(onClick = { presenter.zoom(-1) }, enabled = state.zoom > PdfSession.ZoomLevels.first()) { Text("縮小") }
        Text("${state.zoom}%")
        TextButton(onClick = { presenter.zoom(1) }, enabled = state.zoom < PdfSession.ZoomLevels.last()) { Text("拡大") }
        Text(state.name.orEmpty(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PdfPageContent(state: PdfState<PdfPageImage>, modifier: Modifier) {
    val paragraphs = state.layouts[state.page].orEmpty()
    val translations = state.translations[state.page].orEmpty()
    var hovered by remember(state.name, state.page, state.sideBySide, state.target) { mutableStateOf<Int?>(null) }
    val onHover: (Int?) -> Unit = { hovered = it }
    val image = state.images[state.page]?.content
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Surface(Modifier.weight(1f).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
            if (image == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (state.page in state.undrawable) "ページを表示できませんでした。" else "ページを描画中…")
                }
            } else {
                PdfPageCanvas(image, state.page, state.zoom, paragraphs,
                    if (state.sideBySide) emptyMap() else translations, hovered, onHover)
            }
        }
        if (state.sideBySide) {
            Surface(Modifier.weight(1f).fillMaxHeight(), color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (translations.isEmpty()) Text("訳文はここに表示されます。")
                    SelectionContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for ((index, translation) in translations.toSortedMap()) {
                                Text(translation, Modifier.fillMaxWidth().paragraphHover(index, onHover)
                                    .background(if (hovered == index) MaterialTheme.colorScheme.tertiaryContainer else Color.Transparent)
                                    .padding(5.dp))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PdfPageCanvas(
    image: PdfPageImage, page: Int, zoom: Int, paragraphs: List<PdfParagraph>, translations: Map<Int, String>,
    hovered: Int?, onHover: (Int?) -> Unit,
) {
    val vertical = androidx.compose.runtime.key(page) { rememberScrollState() }
    val horizontal = androidx.compose.runtime.key(page) { rememberScrollState() }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = minOf((maxWidth - 16.dp).coerceAtLeast(1.dp), image.width.dp) * (zoom / 100f)
        val height = width * (image.height / image.width)
        Box(Modifier.fillMaxSize().verticalScroll(vertical).horizontalScroll(horizontal), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.requiredSize(width, height).background(Color.White)) {
                Image(image.bitmap, "PDF 原文", Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
                for ((index, translation) in translations) {
                    val paragraph = paragraphs.getOrNull(index) ?: continue
                    if (translation.isBlank()) continue
                    Box(Modifier.position(paragraph.box, width, height).paragraphHover(index, onHover)) {
                        if (hovered != index) {
                            val font = (paragraph.lines.first().height * height.value * 0.82f).coerceIn(5f, 36f)
                            Text(translation, Modifier.fillMaxSize().background(Color(0xFFFAFAF6)), color = Color(0xFF192628),
                                autoSize = TextAutoSize.StepBased(minFontSize = 4.sp, maxFontSize = font.sp),
                                style = MaterialTheme.typography.bodySmall.copy(lineHeight = androidx.compose.ui.unit.TextUnit.Unspecified),
                                overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                hovered?.let { paragraphs.getOrNull(it) }?.lines?.forEach { anchor ->
                    Box(Modifier.position(anchor, width, height).background(Color(0x70FFC000)).border(1.dp, Color(0xFFB87900)))
                }
            }
        }
        VerticalScrollbar(rememberScrollbarAdapter(vertical), Modifier.align(Alignment.CenterEnd).fillMaxHeight())
    }
}

@OptIn(ExperimentalComposeUiApi::class)
private fun Modifier.paragraphHover(index: Int, onHover: (Int?) -> Unit): Modifier =
    onPointerEvent(PointerEventType.Enter) { onHover(index) }.onPointerEvent(PointerEventType.Exit) { onHover(null) }

private fun Modifier.position(rect: PdfRect, width: Dp, height: Dp): Modifier =
    offset(width * rect.left, height * rect.top).requiredSize(width * rect.width, height * rect.height)
