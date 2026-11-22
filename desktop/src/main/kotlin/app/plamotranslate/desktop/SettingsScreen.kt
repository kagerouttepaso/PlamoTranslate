package app.plamotranslate.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.pipeline.translation.ModelState

private enum class Category(val label: String) {
    MODEL("翻訳モデル"), RECOGNITION("音声認識"), PERFORMANCE("性能"), APPEARANCE("画面の色"),
    CACHE("翻訳キャッシュ"), LOGS("記録"), ABOUT("このアプリについて"), LICENSE("ライセンス"),
}

@Composable
internal fun SettingsScreen(state: SettingsState, presenter: SettingsPresenter, recognition: @Composable () -> Unit) {
    var category by rememberSaveable { mutableStateOf(Category.MODEL) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(DesktopSize.margin)) {
        Column(Modifier.width(DesktopSize.settingsWidth).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(DesktopSize.small)) {
            Category.entries.forEach { item -> NavigationItem(item.label, category == item) { category = item } }
        }
        VerticalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        key(category) {
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                Text(category.label, style = MaterialTheme.typography.titleLarge)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                when (category) {
                    Category.MODEL -> {
                        ModelSummary(state.modelStatus)
                        ModelSettings(state, presenter)
                    }
                    Category.RECOGNITION -> recognition()
                    Category.PERFORMANCE -> PerformanceSettings(state, presenter)
                    Category.APPEARANCE -> {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
                            ThemeMode.entries.forEach { mode ->
                                DesktopChoice(state.theme == mode, { presenter.selectTheme(mode) }, { Text(mode.label) }, !state.busy)
                            }
                        }
                    }
                    Category.CACHE -> CacheSettings(state, presenter)
                    Category.LOGS -> {
                        SettingToggle("詳しい記録を残す", state.detailedLogs, !state.busy, presenter::setDetailedLogs)
                        Text("オンにすると、テキスト・音声翻訳の原文と訳文をこの PC に記録します。オフにしても以前の記録は残ります。")
                        DesktopOutline(presenter::openLogs) { Text("ログフォルダーを開く") }
                    }
                    Category.ABOUT -> AboutDesktop()
                    Category.LICENSE -> DesktopLicenses()
                }
                state.download?.let { ModelDownloadControls(it, presenter::cancelDownload) }
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun CacheSettings(state: SettingsState, presenter: SettingsPresenter) {
    var confirm by remember { mutableStateOf(false) }
    LaunchedEffect(presenter) { presenter.refreshCacheCount() }
    Text("PDF の段落を訳した結果をこの PC に保存し、次に同じ文を訳すときに使います。")
    Text(state.cacheCount?.let { "保存済み：${it} 件" } ?: "件数を確認中…")
    DesktopOutline({ confirm = true }, enabled = !state.busy && state.cacheCount != 0L) { Text("翻訳キャッシュを消去…") }
    state.cacheNotice?.let { Text(it) }
    if (confirm) AlertDialog(onDismissRequest = { confirm = false },
        title = { Text("翻訳キャッシュを消去しますか？") },
        text = { Text("次に PDF を開くと、必要な段落をもう一度翻訳します。保存した PDF はそのまま使えます。") },
        confirmButton = { DesktopButton({ confirm = false; presenter.clearCaches() }) { Text("消去") } },
        dismissButton = { DesktopLink({ confirm = false }) { Text("取り消し") } })
}

@Composable
private fun PerformanceSettings(state: SettingsState, presenter: SettingsPresenter) {
    val enabled = !state.busy && state.model !is ModelState.Loading
    val ready = enabled && state.model is ModelState.Ready
    BackendSettings(state, presenter)
    val automaticThreads = state.automaticThreads?.let { "自動（$it）" } ?: "自動"
    val counts = (listOf(1, 2, 4, 6, 8) + listOfNotNull(state.automaticThreads, state.threads.takeIf { it > 0 })).distinct().sorted()
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        CountMenu("スレッド数", state.threads.takeIf { it > 0 }, automaticThreads, counts, enabled) { presenter.setThreads(it ?: 0) }
        CountMenu("同時に訳す数", state.manualParallel, "自動（${state.automaticParallel}）",
            1..TranslationEngine.MAX_PARALLEL, ready, presenter::setParallel)
    }
    val cpu = state.cpuOptions
    SettingToggle("スレッドを常駐させる", cpu.threadPool, enabled) { presenter.setCpuOptions(cpu.copy(threadPool = it)) }
    SettingToggle("待つ間も CPU を回し続ける", cpu.spinWait, enabled && cpu.threadPool) { presenter.setCpuOptions(cpu.copy(spinWait = it)) }
    SettingToggle("メモリマップで読む", cpu.mmap, enabled) { presenter.setCpuOptions(cpu.copy(mmap = it)) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        DesktopOutline(presenter::measureSpeed, enabled = ready) { Text("速さを測る") }
        DesktopOutline(presenter::tuneParallel, enabled = ready) { Text("同時に訳す数を自動調整") }
        DesktopLink(presenter::resetPerformance, enabled = enabled) { Text("既定に戻す") }
    }
    state.tuningStatus?.let { Text(it) }
}

@Composable
private fun SettingToggle(label: String, checked: Boolean, enabled: Boolean, change: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .heightIn(min = DesktopSize.control), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
        Checkbox(checked, null, enabled = enabled)
        Text(label)
    }
}
