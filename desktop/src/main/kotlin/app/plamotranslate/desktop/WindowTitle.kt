package app.plamotranslate.desktop

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState

internal object WindowsFrame {
    val available = try {
        System.loadLibrary("mockingword-window")
        true
    } catch (error: UnsatisfiedLinkError) {
        app.plamotranslate.log.Logs.w("Windows の標準タイトルバーで開きます。", error)
        false
    }
    external fun install(handle: Long, titleHeight: Int, buttonWidth: Int): Boolean
}

@Composable
internal fun WindowTitle(state: WindowState, close: () -> Unit) {
    val maximized = state.placement == WindowPlacement.Maximized
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(Modifier.fillMaxWidth().height(DesktopSize.titleHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(Modifier.width(DesktopSize.margin))
            Image(painterResource("mockingword.png"), null, Modifier.size(DesktopSize.logo))
            Spacer(Modifier.width(DesktopSize.gap))
            Text(buildAnnotatedString {
                append(PRODUCT_NAME.substringBefore("word"))
                withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) { append("word") }
            }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.weight(1f))
            CaptionButton("最小化", "−") { state.isMinimized = true }
            CaptionButton(if (maximized) "元に戻す" else "最大化", if (maximized) "❐" else "□") {
                state.placement = if (maximized) WindowPlacement.Floating else WindowPlacement.Maximized
            }
            CaptionButton("閉じる", "×", close)
        }
    }
}

@Composable
private fun CaptionButton(name: String, symbol: String, action: () -> Unit) {
    Box(Modifier.width(DesktopSize.captionWidth).fillMaxHeight()
        .clickable(role = Role.Button, onClick = action).semantics { contentDescription = name },
        contentAlignment = Alignment.Center) {
        Text(symbol, style = MaterialTheme.typography.titleMedium)
    }
}
