package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import app.plamotranslate.llm.Language

/*
 * テキスト翻訳の画面（PC）。Android 版のテキスト翻訳（言語の帯・原文・訳文・操作）を、横に広いウィンドウに合わせて
 * 原文と訳文を左右に並べる形にしたもの。原文の欄の下に入力の操作（マイク・貼り付け・消去）、訳文の欄の下に訳の情報
 * （生成したトークン数とかかった時間）とコピーを置き、それぞれの欄に関わる操作を欄のそばに集める。
 * 翻訳は Ctrl+Enter でも始められる（文を打ってからマウスに持ち替えなくてよい）。
 */


@Composable
internal fun TextScreen(
    state: TranslationState,
    presenter: TextPresenter,
    selectTarget: (Language) -> Unit,
    openSettings: () -> Unit,
) {
    val canEdit = state.voice == TextVoicePhase.IDLE
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { event ->
            if (event.type == KeyEventType.KeyDown && event.isCtrlPressed && event.key == Key.Enter && state.canTranslate) {
                presenter.translate()
                true
            } else {
                false
            }
        },
        verticalArrangement = Arrangement.spacedBy(DesktopSize.gap),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
            LanguageMenu("${state.source?.label ?: "自動判定"} ▾", listOf(null) + Language.entries, presenter::selectSource, canEdit) {
                it?.label ?: "自動判定"
            }
            DesktopLink(onClick = presenter::swap, enabled = state.canSwap) { Text("⇄", style = MaterialTheme.typography.titleLarge) }
            LanguageMenu("${state.target.label} ▾", Language.entries, selectTarget, canEdit)
            Spacer(Modifier.weight(1f))
            val model = state.modelStatus
            DesktopLink(onClick = openSettings) {
                Text(if (model.isError) model.label else "翻訳モデル：${model.label}", maxLines = 1,
                    color = if (model.isError) MaterialTheme.colorScheme.error else Color.Unspecified)
            }
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(DesktopSize.margin)) {
            SourcePane(state, presenter, canEdit, Modifier.weight(1f).fillMaxHeight())
            OutputPane(state, presenter, Modifier.weight(1f).fillMaxHeight())
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DesktopSize.gap)) {
            if (state.translating) {
                DesktopOutline(onClick = presenter::cancel) { Text("翻訳を取り消す") }
            } else {
                DesktopButton(onClick = presenter::translate, enabled = state.canTranslate) {
                    Text(if (state.output.isNotBlank()) "訳し直す" else "翻訳")
                }
            }
            Text("Ctrl+Enter で翻訳", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 原文の欄。下の帯に入力の操作を置く。 */
@Composable
private fun SourcePane(state: TranslationState, presenter: TextPresenter, canEdit: Boolean, modifier: Modifier) {
    Pane(modifier, "原文", MaterialTheme.colorScheme.primary) {
        TextField(
            value = state.input,
            onValueChange = presenter::edit,
            readOnly = !canEdit,
            textStyle = DesktopSize.paneText,
            placeholder = { Text("訳したい文を入力・貼り付け", style = DesktopSize.paneText) },
            colors = TextFieldDefaults.colors(
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
                disabledContainerColor = Color.Transparent,
                focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                unfocusedIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.weight(1f).fillMaxWidth(),
        )
        PaneBar {
            DesktopLink(
                onClick = if (state.voice == TextVoicePhase.LISTENING) presenter::stopVoice else presenter::startVoice,
                enabled = canEdit && !state.translating || state.voice == TextVoicePhase.LISTENING,
            ) {
                Text(when (state.voice) {
                    TextVoicePhase.IDLE -> "マイクで入力"
                    TextVoicePhase.LOADING -> "マイクを準備中…"
                    TextVoicePhase.LISTENING -> "聞き取りを止める"
                    TextVoicePhase.FINISHING -> "書き起こし中…"
                })
            }
            DesktopLink(onClick = presenter::pasteAndTranslate, enabled = canEdit) { Text("貼り付けて翻訳") }
            Spacer(Modifier.weight(1f))
            if (state.input.isNotEmpty()) {
                Text("${state.input.length}文字", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            DesktopLink(onClick = presenter::copyInput, enabled = state.input.isNotBlank() && canEdit) { Text("コピー") }
            DesktopLink(onClick = presenter::clear, enabled = canEdit && state.input.isNotEmpty()) { Text("消去") }
        }
    }
}

/** 訳文の欄。訳している途中の訳を流し、下の帯にかかった時間とコピーを置く。 */
@Composable
private fun OutputPane(state: TranslationState, presenter: TextPresenter, modifier: Modifier) {
    Pane(modifier, "訳文", MaterialTheme.colorScheme.secondary) {
        Box(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(DesktopSize.margin)) {
            SelectionContainer {
                Text(
                    state.output.ifEmpty { if (state.translating) "…" else "" },
                    style = DesktopSize.paneText,
                    color = if (state.translating) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f) else MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        PaneBar {
            Text(
                if (state.translating) "翻訳中…" else state.stats,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            DesktopLink(onClick = presenter::copyOutput, enabled = state.canCopy) { Text("コピー") }
        }
    }
}

@Composable
private fun Pane(modifier: Modifier, title: String, accent: Color, content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = MaterialTheme.shapes.large) {
        Column(Modifier.fillMaxSize()) {
            Spacer(Modifier.fillMaxWidth().height(DesktopSize.small).background(accent))
            Text(title, Modifier.padding(start = DesktopSize.margin, top = DesktopSize.gap),
                style = MaterialTheme.typography.labelLarge, color = accent)
            content()
        }
    }
}

@Composable
private fun PaneBar(content: @Composable androidx.compose.foundation.layout.FlowRowScope.() -> Unit) {
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    androidx.compose.foundation.layout.FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = DesktopSize.small),
        itemVerticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}
