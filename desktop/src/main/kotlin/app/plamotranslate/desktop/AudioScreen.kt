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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.plamotranslate.pipeline.conversation.conversationLabel

@Composable
internal fun AudioScreen(state: AudioState, model: ModelStatus, presenter: AudioPresenter, openSettings: () -> Unit) {
    val selection = state.selection
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
                    LanguageMenu("相手の言語　${selection.partner.conversationLabel}", state.partnerOptions, presenter::selectPartner, state.canConfigure) {
                        it.conversationLabel
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
        if (selection.source == AudioSource.MICROPHONE) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("話す人")
                FilterChip(
                    selected = !selection.partnerSpeaking, enabled = state.canConfigure,
                    onClick = { presenter.selectSpeaker(false) }, label = { Text("自分") },
                )
                FilterChip(
                    selected = selection.partnerSpeaking, enabled = state.canConfigure,
                    onClick = { presenter.selectSpeaker(true) }, label = { Text("相手") },
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = presenter::start, enabled = state.canConfigure) { Text("開始") }
            OutlinedButton(onClick = presenter::stop, enabled = state.canStop) { Text("停止") }
            OutlinedButton(onClick = presenter::cut, enabled = state.canCut) { Text("ここで区切る") }
            if (selection.source == AudioSource.PLAYBACK) {
                OutlinedButton(onClick = { presenter.showSubtitle(!state.subtitleVisible) }) {
                    Text(if (state.subtitleVisible) "字幕を隠す" else "字幕を表示")
                }
            }
        }
        Text(state.phase.label, style = MaterialTheme.typography.bodySmall)
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth(), reverseLayout = true,
            verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
        ) {
            items(state.cards, key = AudioCard::key) { AudioCardView(it) }
        }
    }
}

@Composable
private fun AudioCardView(card: AudioCard) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                listOfNotNull(card.language?.label, card.status).joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
            )
            SelectionContainer {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (card.original.isNotBlank()) Text(card.original, style = MaterialTheme.typography.bodyMedium)
                    if (card.translation.isNotBlank()) Text(card.translation, style = MaterialTheme.typography.titleMedium)
                }
            }
            card.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}
