package app.plamotranslate.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.rememberWindowState
import java.awt.Dimension

@Composable
internal fun ApplicationScope.SubtitleWindow(audio: AudioState, preferences: AudioPreferences, hide: () -> Unit) {
    val bounds = remember(preferences) { preferences.loadBounds() }
    val position = bounds?.let { WindowPosition(it.x.dp, it.y.dp) } ?: WindowPosition.Aligned(Alignment.BottomCenter)
    val state = rememberWindowState(position = position, width = (bounds?.width ?: 720f).dp, height = (bounds?.height ?: 180f).dp)
    if (audio.subtitleVisible) {
        Window(
            onCloseRequest = hide, title = "$PRODUCT_NAME 字幕", state = state,
            alwaysOnTop = true, undecorated = true, transparent = true,
        ) {
            DisposableEffect(window) {
                window.minimumSize = Dimension(280, 110)
                onDispose { state.bounds()?.let(preferences::saveBounds) }
            }
            DesktopTheme {
                Column(
                    Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface.copy(alpha = 0.90f)).padding(horizontal = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(DesktopSize.small),
                ) {
                    WindowDraggableArea(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("字幕", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
                            DesktopLink(onClick = hide) { Text("閉じる") }
                        }
                    }
                    val subtitle = audio.subtitle
                    if (subtitle == null) {
                        Text(audio.error ?: audio.phase.label, style = MaterialTheme.typography.bodyMedium)
                    } else {
                        Text(subtitle.original, maxLines = 3, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                        Text(subtitle.translation, maxLines = 4, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleLarge)
                    }
                }
            }
        }
    }
}

private fun WindowState.bounds(): SubtitleBounds? {
    val position = position as? WindowPosition.Absolute ?: return null
    return SubtitleBounds(position.x.value, position.y.value, size.width.value, size.height.value)
}
