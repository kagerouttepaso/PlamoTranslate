package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.pipeline.translation.ModelState

@Composable
internal fun MainScreen(components: DesktopComponents) {
    val settings by components.settings.state.collectAsState()
    when (settings.initialSetup) {
        null -> Surface(Modifier.fillMaxSize()) { Text("設定を読み込み中…", Modifier.padding(DesktopSize.margin)) }
        true -> Surface(Modifier.fillMaxSize()) {
            val audio by components.audio.state.collectAsState()
            ModelSetupScreen(settings, components.settings, audio.selection.recognition.model, initial = true)
        }
        false -> DesktopPages(components, settings)
    }
}

@Composable
private fun DesktopPages(components: DesktopComponents, settings: SettingsState) {
    val page by components.navigation.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(DesktopSize.navWidth).fillMaxHeight()) {
                Column(Modifier.padding(DesktopSize.gap), verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
                    Page.entries.forEach { item ->
                        NavigationItem(item.label, item == page) { components.navigation.select(item) }
                    }
                }
            }
            VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Column(Modifier.weight(1f).fillMaxHeight().padding(DesktopSize.margin), verticalArrangement = Arrangement.spacedBy(DesktopSize.margin)) {
                Text(page.label, style = MaterialTheme.typography.headlineSmall)
                when (page) {
                    Page.TEXT -> {
                        DisposableEffect(components.text) { onDispose(components.text::leave) }
                        val text by components.text.state.collectAsState()
                        TextScreen(text, components.text, components.selectTranslationTarget) {
                            components.navigation.select(Page.SETTINGS)
                        }
                    }
                    Page.SETTINGS -> {
                        val audio by components.audio.state.collectAsState()
                        SettingsScreen(settings, components.settings) {
                            RecognitionSettings(audio, components.audio, settings, components.settings)
                        }
                    }
                    Page.AUDIO -> {
                        val audio by components.audio.state.collectAsState()
                        val recognition = audio.selection.recognition.model
                        if (settings.hasRecognition(recognition)) {
                            AudioScreen(audio, components.audio.level, settings.modelStatus, components.audio) {
                                components.navigation.select(Page.SETTINGS)
                            }
                        } else {
                            ModelSetupScreen(settings, components.settings, recognition, initial = false)
                        }
                    }
                    Page.PDF -> {
                        val pdf by components.pdf.state.collectAsState()
                        val documents by components.pdf.documents.collectAsState()
                        val view by components.pdf.view.collectAsState()
                        PdfScreen(pdf, documents, view, components.pdf, components.selectTranslationTarget)
                    }
                }
            }
        }
    }
}

@Composable
internal fun <T : Language?> LanguageMenu(
    label: String,
    options: List<T>,
    select: (T) -> Unit,
    enabled: Boolean = true,
    optionLabel: (T) -> String = { it?.label ?: "自動" },
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesktopOutline(onClick = { expanded = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = DesktopSize.menuMax)) {
            options.forEach { item ->
                DropdownMenuItem(text = { Text(optionLabel(item)) }, onClick = {
                    expanded = false
                    select(item)
                })
            }
        }
    }
}

@Composable
private fun SettingsScreen(state: SettingsState, presenter: SettingsPresenter, recognition: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.medium) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(DesktopSize.margin),
            verticalArrangement = Arrangement.spacedBy(DesktopSize.gap),
        ) {
            Text("翻訳モデル", style = MaterialTheme.typography.titleLarge)
            ModelSummary(state.modelStatus)
            ModelSettings(state, presenter)
            state.download?.let { ModelDownloadControls(it, presenter::cancelDownload) }
            Text("性能", style = MaterialTheme.typography.titleLarge)
            BackendSettings(state, presenter)
            val automaticThreads = state.automaticThreads?.let { "自動（$it）" } ?: "自動"
            val threadChoices = (listOf(1, 2, 4, 6, 8) + listOfNotNull(state.automaticThreads, state.threads.takeIf { it > 0 }))
                .distinct().sorted()
            Row(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                CountMenu(
                    "スレッド数", state.threads.takeIf { it > 0 }, automaticThreads, threadChoices,
                    !state.busy && state.model !is ModelState.Loading, { presenter.setThreads(it ?: 0) },
                )
                CountMenu(
                    "同時に訳す数", state.manualParallel, "自動（${state.automaticParallel}）", 1..TranslationEngine.MAX_PARALLEL,
                    !state.busy && state.model is ModelState.Ready, presenter::setParallel,
                )
            }
            DesktopOutline(onClick = presenter::tuneParallel, enabled = !state.busy && state.model is ModelState.Ready) {
                Text("速さを調整")
            }
            state.tuningStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            recognition()
            Text("ログ", style = MaterialTheme.typography.titleLarge)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                androidx.compose.material3.Switch(
                    checked = state.detailedLogs, onCheckedChange = presenter::setDetailedLogs, enabled = !state.busy,
                )
                Text("詳しい記録を残す")
            }
            Text("オンにすると、テキスト・音声翻訳の原文と訳文をこの PC に記録します。オフにしても以前の記録は消えません。")
            Text("保存先: %LOCALAPPDATA%\\Mockingword\\logs", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
internal fun CountMenu(
    label: String,
    selected: Int?,
    automatic: String,
    choices: Iterable<Int>,
    enabled: Boolean,
    select: (Int?) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        DesktopOutline(onClick = { expanded = true }, enabled = enabled) {
            Text("$label ${selected ?: automatic}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = DesktopSize.menuMax)) {
            DropdownMenuItem(text = { Text(automatic) }, onClick = { expanded = false; select(null) })
            choices.forEach { count ->
                DropdownMenuItem(text = { Text(count.toString()) }, onClick = { expanded = false; select(count) })
            }
        }
    }
}

@Composable
internal fun ModelSummary(status: ModelStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    Column(modifier) {
        Text(status.label, color = if (status.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        status.detail?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, maxLines = if (compact) 1 else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis)
        }
    }
}
