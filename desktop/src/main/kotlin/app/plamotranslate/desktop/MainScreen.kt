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
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
    val page by components.navigation.state.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.surfaceContainerLow, modifier = Modifier.width(184.dp).fillMaxHeight()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Mockingword", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                    Page.entries.forEach { item ->
                        NavigationDrawerItem(
                            label = { Text(item.label) },
                            selected = item == page,
                            onClick = { components.navigation.select(item) },
                        )
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight().padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(page.label, style = MaterialTheme.typography.headlineSmall)
                when (page) {
                    Page.TEXT -> {
                        val text by components.text.state.collectAsState()
                        TextScreen(text, components.text, components.selectTranslationTarget) {
                            components.navigation.select(Page.SETTINGS)
                        }
                    }
                    Page.SETTINGS -> {
                        val settings by components.settings.state.collectAsState()
                        val audio by components.audio.state.collectAsState()
                        SettingsScreen(settings, components.settings) { RecognitionSettings(audio, components.audio) }
                    }
                    Page.AUDIO -> {
                        val audio by components.audio.state.collectAsState()
                        val settings by components.settings.state.collectAsState()
                        AudioScreen(audio, settings.modelStatus, components.audio) { components.navigation.select(Page.SETTINGS) }
                    }
                    Page.PDF -> {
                        DisposableEffect(components.pdf) {
                            components.pdf.setVisible(true)
                            onDispose { components.pdf.setVisible(false) }
                        }
                        val pdf by components.pdf.state.collectAsState()
                        PdfScreen(pdf, components.pdf, components.selectTranslationTarget)
                    }
                }
            }
        }
    }
}

@Composable
private fun TextScreen(
    state: TranslationState, presenter: TextPresenter, selectTarget: (Language) -> Unit, openSettings: () -> Unit,
) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ModelSummary(state.modelStatus, Modifier.weight(1f), compact = true)
            TextButton(onClick = openSettings) { Text("モデルの設定") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            LanguageMenu("原文: ${state.sourceLabel}", listOf(null) + Language.entries, presenter::selectSource)
            LanguageMenu("訳す先: ${state.target.label}", Language.entries, selectTarget)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            OutlinedTextField(
                value = state.input,
                onValueChange = presenter::edit,
                label = { Text("原文") },
                placeholder = { Text("訳したい文を入力") },
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
            OutlinedTextField(
                value = state.output,
                onValueChange = {},
                readOnly = true,
                label = { Text("訳文") },
                colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
                modifier = Modifier.weight(1f).fillMaxHeight(),
            )
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Button(onClick = presenter::translate, enabled = state.canTranslate) { Text("翻訳する") }
            OutlinedButton(onClick = presenter::cancel, enabled = state.translating) { Text("取り消す") }
            OutlinedButton(onClick = presenter::copyOutput, enabled = state.canCopy) { Text("訳文をコピー") }
            if (state.translating) Text("翻訳中…", style = MaterialTheme.typography.bodySmall)
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
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) { Text(label) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
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
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("翻訳モデル", style = MaterialTheme.typography.titleLarge)
            ModelSummary(state.modelStatus)
            ModelSettings(state, presenter)
            Text("性能", style = MaterialTheme.typography.titleLarge)
            BackendSettings(state, presenter)
            val automaticThreads = state.automaticThreads?.let { "自動（$it）" } ?: "自動"
            val threadChoices = (listOf(1, 2, 4, 6, 8) + listOfNotNull(state.automaticThreads, state.threads.takeIf { it > 0 }))
                .distinct().sorted()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CountMenu(
                    "スレッド数", state.threads.takeIf { it > 0 }, automaticThreads, threadChoices,
                    !state.busy && state.model !is ModelState.Loading, { presenter.setThreads(it ?: 0) },
                )
                CountMenu(
                    "同時に訳す数", state.manualParallel, "自動（${state.automaticParallel}）", 1..TranslationEngine.MAX_PARALLEL,
                    !state.busy && state.model is ModelState.Ready, presenter::setParallel,
                )
            }
            OutlinedButton(onClick = presenter::tuneParallel, enabled = !state.busy && state.model is ModelState.Ready) {
                Text("速さを調整")
            }
            state.tuningStatus?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            recognition()
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
        OutlinedButton(onClick = { expanded = true }, enabled = enabled) {
            Text("$label ${selected ?: automatic}")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }, modifier = Modifier.heightIn(max = 320.dp)) {
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
