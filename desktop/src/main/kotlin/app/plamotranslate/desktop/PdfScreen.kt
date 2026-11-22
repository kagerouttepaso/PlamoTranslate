package app.plamotranslate.desktop

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plamotranslate.desktop.pdf.PdfBookmark
import app.plamotranslate.desktop.pdf.PdfPageNumber
import app.plamotranslate.desktop.pdf.PdfScale
import app.plamotranslate.desktop.pdf.PdfSpread
import app.plamotranslate.desktop.pdf.PdfZoom
import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.pdf.PdfPhase
import app.plamotranslate.pipeline.pdf.PdfState
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.net.URI

/*
 * PDF 翻訳の画面（PC）。ブラウザー版の PDF 翻訳と同じく、開いた PDF はまずビューアで読むだけにし、
 * 「翻訳を開始」で訳し始める。訳したページは訳を書き込んだ PDF のページに置き換わり、原文/訳文の切り替えで
 * 元のページと見比べられる。訳している途中でも中止・再開・保存ができる。
 *
 * 上の帯：ファイルと翻訳の操作（開く・訳す先・原文/訳文・保存・開始/中止/再開）と進み具合。
 * 左の欄：見出し（訳した見出しは訳で出す）。中央：ページ。ページの帯：戻る/進む・ページ番号・拡大率・並べ方・検索。
 */

@OptIn(ExperimentalFoundationApi::class, ExperimentalComposeUiApi::class)
@Composable
internal fun PdfScreen(
    state: PdfState,
    documents: PdfDocuments,
    view: PdfViewState,
    presenter: PdfPresenter,
    selectTarget: (Language) -> Unit,
) {
    val dropTarget = remember(presenter) {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val files = event.dragData() as? DragData.FilesList ?: return false
                presenter.drop(files.readFiles())
                return true
            }
        }
    }
    Column(
        Modifier.fillMaxSize().dragAndDropTarget({ it.dragData() is DragData.FilesList }, dropTarget),
        verticalArrangement = Arrangement.spacedBy(DesktopSize.gap),
    ) {
        TranslationBar(state, documents, view, presenter, selectTarget)
        StatusLine(state, documents, view, presenter)
        val original = documents.original
        if (original == null || state.name == null) {
            EmptyState(state, presenter)
        } else {
            val scope = rememberCoroutineScope()
            val model = remember(documents.documentId) { PdfViewerModel(scope, presenter, checkNotNull(documents.documentId), ::openExternal) }
            LaunchedEffect(model, original) { model.load(original) }
            Viewer(model, state, documents, view, presenter)
        }
    }
}

/** ファイルと翻訳の操作。主な操作（開始・再開、訳し終えたら保存）だけを塗りのボタンにする。 */
@Composable
private fun TranslationBar(
    state: PdfState,
    documents: PdfDocuments,
    view: PdfViewState,
    presenter: PdfPresenter,
    selectTarget: (Language) -> Unit,
) {
    val opened = state.name != null && state.pageCount > 0
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        DesktopOutline(onClick = presenter::choose) { Text("PDF を開く") }
        Text(
            state.name ?: "", style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = true),
        )
        LanguageMenu("訳す先：${state.target.label} ▾", Language.entries, selectTarget)
        if (opened) {
            SingleChoiceSegmentedButtonRow {
                val canCompare = state.phase != PdfPhase.Idle
                SegmentedButton(
                    selected = !view.showTranslation || !canCompare, onClick = { presenter.showTranslation(false) },
                    colors = desktopSegmentColors(), shape = SegmentedButtonDefaults.itemShape(0, 2), enabled = canCompare,
                ) { Text("原文") }
                SegmentedButton(
                    selected = view.showTranslation && canCompare, onClick = { presenter.showTranslation(true) },
                    colors = desktopSegmentColors(), shape = SegmentedButtonDefaults.itemShape(1, 2), enabled = canCompare,
                ) { Text("訳文") }
            }
            val finished = state.phase == PdfPhase.Finished
            val canSave = state.writtenPages.isNotEmpty() && !view.saving
            val saveLabel = if (view.saving) "保存中…" else "訳した PDF を保存"
            if (finished) {
                DesktopButton(onClick = presenter::save, enabled = canSave) { Text(saveLabel) }
            } else {
                DesktopOutline(onClick = presenter::save, enabled = canSave) { Text(saveLabel) }
                when {
                    state.phase == PdfPhase.Translating -> DesktopOutline(onClick = presenter::stop) { Text("翻訳を中止") }
                    state.untranslated.isNotEmpty() -> DesktopButton(onClick = presenter::retry) { Text("やり直す") }
                    state.phase == PdfPhase.Paused -> DesktopButton(onClick = presenter::start) { Text("翻訳を再開") }
                    else -> DesktopButton(onClick = presenter::start, enabled = !state.opening) { Text("翻訳を開始") }
                }
            }
        }
    }
}

/** 進み具合と通知。訳していないときは、開いた・止めた・終えたの状態を1行で示す。 */
@Composable
private fun StatusLine(state: PdfState, documents: PdfDocuments, view: PdfViewState, presenter: PdfPresenter) {
    val error = MaterialTheme.colorScheme.error
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val written = state.writtenPages.size
    val total = state.pageCount
    val fonts = documents.fonts
    val (message, isError) = when {
        view.notice != null -> view.notice to view.noticeIsError
        state.openFailed -> "この PDF を開けませんでした（壊れているか、パスワードがかかっています）" to true
        documents.error != null -> documents.error to true
        fonts is PdfFontStatus.Failed -> "訳を書き込むフォントを用意できません：${fonts.message}" to true
        state.opening -> "開いています…" to false
        state.untranslated.isNotEmpty() -> "訳せなかった段落があり、翻訳を止めました。「やり直す」で続きから訳します" to true
        state.phase == PdfPhase.Translating && fonts is PdfFontStatus.Loading ->
            "訳を書き込むフォントを取得しています… ${megabytes(fonts.done)} / ${megabytes(fonts.total)} MB" to false
        state.phase == PdfPhase.Translating -> buildString {
            append("翻訳中：訳したページ $written / $total")
            state.translationPage?.let { append("（${PdfPageNumber.fromIndex(it)}ページ目を訳しています）") }
        } to false
        state.phase == PdfPhase.Paused -> "中止しました。訳したページ $written / $total。「翻訳を再開」で続きから訳します" to false
        state.phase == PdfPhase.Finished -> "すべてのページを訳しました（$total ページ）" to false
        total > 0 -> "$total ページ。「翻訳を開始」で、今見ているページから訳します" to false
        else -> null to false
    }
    Column(verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = if (isError) error else muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (state.unreadable.isNotEmpty()) {
                Text("文字を読み取れないページ ${state.unreadable.size}", style = MaterialTheme.typography.labelMedium, color = muted)
            }
            if (view.notice != null) DesktopLink(onClick = presenter::dismissNotice) { Text("閉じる") }
        }
        if (state.phase == PdfPhase.Translating && total > 0) {
            LinearProgressIndicator(progress = { written.toFloat() / total }, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun EmptyState(state: PdfState, presenter: PdfPresenter) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(DesktopSize.margin, Alignment.CenterVertically),
        ) {
            Text(if (state.opening) "開いています…" else "PDF をここにドロップ", style = MaterialTheme.typography.headlineSmall)
            Text(
                "開いた PDF はまず読むだけです。「翻訳を開始」で、見ているページから順に訳を書き込みます。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            DesktopButton(onClick = presenter::choose) { Text("PDF を開く") }
        }
    }
}

/** 見出しの欄・ページの帯・ページ・検索。キーボードとマウスのボタンの操作はここで受ける。 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun Viewer(model: PdfViewerModel, state: PdfState, documents: PdfDocuments, view: PdfViewState, presenter: PdfPresenter) {
    val focus = remember { FocusRequester() }
    val searchFocus = remember { FocusRequester() }
    LaunchedEffect(model) { focus.requestFocus() }
    val scale = PdfScale.percent(view.navigation.dpPerPoint)
    Row(
        Modifier.fillMaxSize().focusRequester(focus).focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when {
                    event.isCtrlPressed && event.key == Key.F -> { model.showSearch(true); searchFocus.requestFocus(); true }
                    event.key == Key.Escape && view.navigation.searchOpen -> { model.closeSearch(); focus.requestFocus(); true }
                    event.key == Key.Escape && view.navigation.selection != null -> { model.clearSelection(); true }
                    event.isCtrlPressed && event.key == Key.C && view.navigation.selection != null -> {
                        view.navigation.selection?.text?.let(::copyText); true
                    }
                    event.isAltPressed && event.key == Key.DirectionLeft -> { model.back(); true }
                    event.isAltPressed && event.key == Key.DirectionRight -> { model.forward(); true }
                    event.isCtrlPressed && (event.key == Key.Equals || event.key == Key.Plus || event.key == Key.NumPadAdd) -> {
                        presenter.setZoom(PdfScale.step(scale, 1)); true
                    }
                    event.isCtrlPressed && (event.key == Key.Minus || event.key == Key.NumPadSubtract) -> {
                        presenter.setZoom(PdfScale.step(scale, -1)); true
                    }
                    event.isCtrlPressed && (event.key == Key.Zero || event.key == Key.NumPad0) -> {
                        presenter.setZoom(PdfZoom.FitWidth); true
                    }
                    else -> false
                }
            }
            .onPointerEvent(PointerEventType.Press) { event ->
                when (event.button) {
                    PointerButton.Back -> model.back()
                    PointerButton.Forward -> model.forward()
                    else -> focus.requestFocus()
                }
            },
    ) {
        if (view.settings.outline && view.navigation.outline.isNotEmpty()) {
            OutlinePane(model, state, view, Modifier.width(232.dp).fillMaxHeight())
            Spacer(Modifier.width(DesktopSize.gap))
        }
        Column(Modifier.weight(1f).fillMaxHeight()) {
            PdfPageBar(model, view, state.page, scale, presenter)
            if (view.navigation.searchOpen) {
                SearchBar(model, documents, view, searchFocus) { model.closeSearch(); focus.requestFocus() }
            }
            PdfPages(model, state, documents, view, presenter, Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** 戻る/進む・ページ番号・拡大率・並べ方・見出しの欄・検索。 */
@Composable
internal fun PdfPageBar(model: PdfViewerModel, view: PdfViewState, page: Int, scale: Int, presenter: PdfPresenter) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer) {
        DesktopToolbar {
            if (view.navigation.outline.isNotEmpty()) {
                DesktopLink(onClick = presenter::toggleOutline) { Text("見出し") }
            }
            DesktopLink(onClick = model::back, enabled = view.navigation.history.canBack) { Text("‹ 戻る", maxLines = 1) }
            DesktopLink(onClick = model::forward, enabled = view.navigation.history.canForward) { Text("進む ›", maxLines = 1) }
            Spacer(Modifier.width(DesktopSize.gap))
            PageField(model, page, view.navigation.sizes.size)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DesktopLink(onClick = { presenter.setZoom(PdfScale.step(scale, -1)) }) { Text("−") }
                ChoiceMenu(
                    "$scale%",
                    listOf(PdfZoom.FitWidth, PdfZoom.FitPage) + listOf(50, 75, 100, 125, 150, 200, 300).map(PdfZoom::Scale),
                    { it.label }, presenter::setZoom,
                )
                DesktopLink(onClick = { presenter.setZoom(PdfScale.step(scale, 1)) }) { Text("+") }
            }
            ChoiceMenu(view.settings.spread.short, PdfSpread.entries, { it.label }, presenter::setSpread)
            DesktopLink(onClick = { model.showSearch(!view.navigation.searchOpen) }) { Text("検索", maxLines = 1) }
        }
    }
}

/** 今のページの番号。番号を打って Enter でそのページへ移る。 */
@Composable
private fun PageField(model: PdfViewerModel, page: Int, count: Int) {
    var text by remember(page) { mutableStateOf(PdfPageNumber.fromIndex(page).toString()) }
    val go = { PdfPageNumber.parseIndex(text, count)?.let { model.goTo(it) } }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small) {
            BasicTextField(
                value = text, onValueChange = { value -> text = value.filter(Char::isDigit).take(5) },
                singleLine = true,
                textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { go() }),
                modifier = Modifier.width(48.dp).padding(DesktopSize.small)
                    .onPreviewKeyEvent { event ->
                        if (event.type == KeyEventType.KeyDown && event.key == Key.Enter) {
                            go()
                            true
                        } else false
                    },
            )
        }
        Text(" / $count", style = MaterialTheme.typography.bodyMedium)
    }
}

/** 検索の帯。Enter で次、Shift+Enter で前、Esc で閉じる。表示している版（原文/訳文）の文字を探す。 */
@Composable
private fun SearchBar(model: PdfViewerModel, documents: PdfDocuments, view: PdfViewState, focus: FocusRequester, close: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val run = { model.find(query) { page -> documents.versionFor(page, view.showTranslation) } }
    LaunchedEffect(Unit) { focus.requestFocus() }
    // 原文/訳文を切り替えたら、表示している側で探し直す。
    LaunchedEffect(view.showTranslation) { if (view.navigation.search.active) run() }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().padding(horizontal = DesktopSize.gap, vertical = DesktopSize.small), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
            Surface(color = MaterialTheme.colorScheme.surface, shape = MaterialTheme.shapes.small, modifier = Modifier.width(280.dp)) {
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = MaterialTheme.colorScheme.onSurface),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().padding(DesktopSize.gap).focusRequester(focus).onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.Enter, Key.NumPadEnter -> {
                                if (view.navigation.search.query != query) run() else model.moveSearch(if (event.isShiftPressed) -1 else 1)
                                true
                            }
                            Key.Escape -> { close(); true }
                            else -> false
                        }
                    },
                )
            }
            val search = view.navigation.search
            Text(
                when {
                    !search.active -> "Enter で検索"
                    search.hits.isEmpty() && search.done -> "見つかりません"
                    search.hits.isEmpty() -> "探しています…"
                    else -> "${search.current + 1} / ${search.hits.size} 件" + if (search.done) "" else "（探しています…）"
                },
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            DesktopLink(onClick = { model.moveSearch(-1) }, enabled = search.hits.isNotEmpty()) { Text("前へ") }
            DesktopLink(onClick = { model.moveSearch(1) }, enabled = search.hits.isNotEmpty()) { Text("次へ") }
            Spacer(Modifier.weight(1f))
            DesktopLink(onClick = close) { Text("閉じる") }
        }
    }
}

/** 見出しの欄。訳文を見ているときは、訳した見出しを訳で出す。▸ で子の見出しを開く。 */
@Composable
private fun OutlinePane(model: PdfViewerModel, state: PdfState, view: PdfViewState, modifier: Modifier) {
    val expanded = remember(model) { mutableStateMapOf<PdfBookmark, Boolean>() }
    val rows = buildList {
        fun visit(items: List<PdfBookmark>, depth: Int) {
            items.forEach { item ->
                add(item to depth)
                if (expanded[item] == true) visit(item.children, depth + 1)
            }
        }
        visit(view.navigation.outline, 0)
    }
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column {
            Text("見出し", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(DesktopSize.margin))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows) { (item, depth) ->
                    val title = if (view.showTranslation) state.titleTranslation(item.title) ?: item.title else item.title
                    Row(
                        Modifier.fillMaxWidth().clickable { model.follow(item.destination) }
                            .padding(start = (8 + depth * 14).dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Box(Modifier.width(18.dp)) {
                            if (item.children.isNotEmpty()) {
                                Text(
                                    if (expanded[item] == true) "▾" else "▸",
                                    modifier = Modifier.clickable { expanded[item] = expanded[item] != true },
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Text(title, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

@Composable
private fun <T> ChoiceMenu(label: String, options: List<T>, optionLabel: (T) -> String, select: (T) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        DesktopLink(onClick = { open = true }) { Text("$label ▾", maxLines = 1) }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, modifier = Modifier.heightIn(max = DesktopSize.menuMax)) {
            options.forEach { option ->
                DropdownMenuItem(text = { Text(optionLabel(option)) }, onClick = { open = false; select(option) })
            }
        }
    }
}

private fun megabytes(bytes: Long) = "%.1f".format(bytes / 1_048_576.0)

private fun copyText(text: String) {
    Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null)
}

/** PDF の外へのリンクは、既定のブラウザー・メールで開く。ほかの種類（ファイルの実行など）は開かない。 */
private fun openExternal(uri: String) {
    val target = runCatching { URI(uri.trim()) }.getOrNull() ?: return
    if (target.scheme?.lowercase() !in setOf("http", "https", "mailto")) return
    runCatching {
        val desktop = Desktop.getDesktop()
        if (target.scheme.equals("mailto", ignoreCase = true)) desktop.mail(target) else desktop.browse(target)
    }
}
