package app.plamotranslate.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.conversation.SwapState
import app.plamotranslate.pipeline.conversation.conversationLabel

@Composable
internal fun AudioScreen(state: AudioState, model: ModelStatus, presenter: AudioPresenter, openSettings: () -> Unit) {
    val selection = state.selection
    val microphone = selection.source == AudioSource.MICROPHONE
    var confirmClear by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            ModelSummary(model, Modifier.weight(1f), compact = true)
            TextButton(onClick = openSettings) { Text("モデルの設定") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("音の出どころ")
            AudioSource.entries.forEach { source ->
                FilterChip(
                    selected = selection.source == source, enabled = state.canConfigure,
                    onClick = { presenter.selectSource(source) }, label = { Text(source.label) },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (selection.source) {
                AudioSource.MICROPHONE -> {
                    LanguageMenu("自分の言語　${selection.self.conversationLabel}", state.selfOptions, presenter::selectSelf, state.canConfigure) {
                        it.conversationLabel
                    }
                    val partner = selection.partner?.conversationLabel
                        ?: state.detectedPartner?.let { "自動（${it.conversationLabel}）" } ?: "自動"
                    LanguageMenu("相手の言語　$partner", state.partnerOptions, presenter::selectPartner, state.canConfigure) {
                        it?.conversationLabel ?: "自動"
                    }
                }
                AudioSource.PLAYBACK -> {
                    LanguageMenu("原文　${selection.playbackFrom.conversationLabel}", state.playbackFromOptions, presenter::selectPlaybackFrom, state.canConfigure) {
                        it.conversationLabel
                    }
                    LanguageMenu("訳す先　${selection.playbackTo.label}", state.playbackToOptions, presenter::selectPlaybackTo, state.canConfigure)
                }
            }
        }
        if (microphone) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("話す人")
                FilterChip(
                    selected = selection.partnerSpeaking == null, enabled = state.canConfigure,
                    onClick = { presenter.selectSpeaker(null) }, label = { Text("自動") },
                )
                FilterChip(
                    selected = selection.partnerSpeaking == false, enabled = state.canConfigure,
                    onClick = { presenter.selectSpeaker(false) }, label = { Text("自分") },
                )
                FilterChip(
                    selected = selection.partnerSpeaking == true, enabled = state.canConfigure && selection.partner != null,
                    onClick = { presenter.selectSpeaker(true) }, label = { Text("相手") },
                )
                FilterChip(selected = selection.faceToFace, onClick = { presenter.setFaceToFace(!selection.faceToFace) },
                    label = { Text("対面") })
            }
            if (!state.automaticAvailable && selection.partnerSpeaking == null) {
                Text("自動判定には言語識別モデルが必要です。設定を確認するか、相手の言語と話す人を選んでください。")
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = presenter::start, enabled = state.canStart) { Text("開始") }
            OutlinedButton(onClick = presenter::stop, enabled = state.canStop) { Text("停止") }
            OutlinedButton(onClick = presenter::cut, enabled = state.canCut) { Text("ここで区切る") }
            if (selection.source == AudioSource.PLAYBACK) {
                OutlinedButton(onClick = { presenter.showSubtitle(!state.subtitleVisible) }) {
                    Text(if (state.subtitleVisible) "字幕を隠す" else "字幕を表示")
                }
            } else {
                TextButton(onClick = presenter::exportLog, enabled = state.canExport) { Text("記録を保存") }
                TextButton(onClick = presenter::copyLog, enabled = state.canExport) { Text("記録をコピー") }
                TextButton(onClick = { confirmClear = true }, enabled = state.canExport && state.canConfigure) { Text("記録を消去") }
            }
        }
        Text(state.phase.label, style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        state.notice?.let { Text(it) }
        if (microphone && selection.faceToFace) {
            AudioCards(state.cards, Modifier.weight(1f).rotate(180f), true, presenter)
            AudioCards(state.cards, Modifier.weight(1f), false, presenter)
        } else {
            AudioCards(state.cards, Modifier.weight(1f), null, presenter)
        }
    }
    if (confirmClear) {
        AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("会話の記録を消去しますか？") },
            confirmButton = { TextButton(onClick = { confirmClear = false; presenter.clear() }) { Text("消去") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取り消し") } })
    }
}

@Composable
private fun AudioCards(cards: List<AudioCard>, modifier: Modifier, partnerReader: Boolean?, presenter: AudioPresenter) {
    LazyColumn(modifier.fillMaxWidth(), reverseLayout = true,
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom)) {
        items(cards, key = AudioCard::key) { card ->
            val right = card.mine != null && card.mine != (partnerReader == true)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (right) Arrangement.End else Arrangement.Start) {
                AudioCardView(card, partnerReader) { card.turnId?.let(presenter::swapLanguage) }
            }
        }
    }
}

@Composable
private fun AudioCardView(card: AudioCard, partnerReader: Boolean?, swap: () -> Unit) {
    val originalFirst = partnerReader == null || card.mine == null || card.mine != partnerReader
    Surface(
        modifier = Modifier.fillMaxWidth(if (card.mine == null) 1f else 0.86f),
        color = when (card.mine) {
            true -> MaterialTheme.colorScheme.primaryContainer
            false -> MaterialTheme.colorScheme.secondaryContainer
            null -> MaterialTheme.colorScheme.surfaceContainerLow
        },
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(card.language?.label, card.status).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (originalFirst) {
                        if (card.original.isNotBlank()) Text(card.original, style = MaterialTheme.typography.bodyMedium)
                        if (card.translation.isNotBlank()) Text(card.translation, style = MaterialTheme.typography.titleMedium)
                    } else {
                        if (card.translation.isNotBlank()) Text(card.translation, style = MaterialTheme.typography.titleMedium)
                        if (card.original.isNotBlank()) Text(card.original, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            if (card.swap == SwapState.AVAILABLE) TextButton(onClick = swap) { Text("話す人を入れ替える") }
            card.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
