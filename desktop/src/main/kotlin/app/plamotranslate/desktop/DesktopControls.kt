package app.plamotranslate.desktop

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
internal fun DesktopButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    Button(onClick, modifier.defaultMinSize(minWidth = 1.dp, minHeight = DesktopSize.control), enabled, shape = MaterialTheme.shapes.small,
        contentPadding = DesktopSize.buttonPadding, content = content)
}

@Composable
internal fun DesktopOutline(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    OutlinedButton(onClick, modifier.defaultMinSize(minWidth = 1.dp, minHeight = DesktopSize.control), enabled, shape = MaterialTheme.shapes.small,
        colors = ButtonDefaults.outlinedButtonColors(contentColor = colors.onSurfaceVariant),
        border = BorderStroke(1.dp, colors.outlineVariant), contentPadding = DesktopSize.buttonPadding, content = content)
}

@Composable
internal fun DesktopLink(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    contentPadding: PaddingValues = DesktopSize.linkPadding, content: @Composable RowScope.() -> Unit) {
    TextButton(onClick, modifier.defaultMinSize(minWidth = 1.dp, minHeight = DesktopSize.control), enabled, shape = MaterialTheme.shapes.small, contentPadding = contentPadding, content = content)
}

@Composable
internal fun DesktopChoice(selected: Boolean, onClick: () -> Unit, label: @Composable () -> Unit, enabled: Boolean = true) {
    val c = MaterialTheme.colorScheme
    FilterChip(selected, onClick, label, enabled = enabled, shape = MaterialTheme.shapes.small,
        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = c.primary, selectedLabelColor = c.onPrimary,
            labelColor = c.onSurfaceVariant))
}

@Composable
internal fun NavigationItem(label: String, selected: Boolean, select: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(color = if (selected) colors.primary else androidx.compose.ui.graphics.Color.Transparent,
        contentColor = if (selected) colors.onPrimary else colors.onSurfaceVariant,
        shape = MaterialTheme.shapes.small) {
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxWidth().heightIn(min = DesktopSize.control).selectable(selected, role = Role.Tab, onClick = select)
                .padding(horizontal = DesktopSize.gap), contentAlignment = androidx.compose.ui.Alignment.CenterStart,
        ) { Text(label, style = MaterialTheme.typography.labelLarge) }
    }
}
