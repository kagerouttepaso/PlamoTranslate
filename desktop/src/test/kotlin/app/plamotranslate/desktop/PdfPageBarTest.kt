package app.plamotranslate.desktop

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import app.plamotranslate.desktop.pdf.PdfBookmark
import app.plamotranslate.desktop.pdf.PdfSpread
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalComposeUiApi::class)
class PdfPageBarTest {
    @Test fun `見出し欄の横で検索が収まらなければボタンの幅を保って折り返す`() = runTest {
        val presenter = PdfPresenter(backgroundScope, DesktopPdfReader(backgroundScope),
            { _, _ -> error("翻訳の依頼はない") }, { null })
        val model = PdfViewerModel(backgroundScope, presenter, 1L) {}
        val view = PdfViewState(settings = PdfViewSettings(spread = PdfSpread.ODD),
            navigation = PdfNavigation(outline = listOf(PdfBookmark("見出し1", null, emptyList()))))

        fun render(width: Int): Pair<Rect, Rect> {
            val scene = ImageComposeScene(width, 160) {
                DesktopTheme(ThemeMode.LIGHT) { PdfPageBar(model, view, 0, 100, presenter) }
            }
            try {
                scene.render().close()
                val buttons = descendants(scene.semanticsOwners.single().rootSemanticsNode)
                    .filter { it.config.contains(SemanticsActions.OnClick) }
                    .associateBy { it.config.getOrNull(SemanticsProperties.Text)?.singleOrNull()?.text }
                return buttons.getValue("見出し").boundsInWindow to buttons.getValue("検索").boundsInWindow
            } finally {
                scene.close()
            }
        }

        // 900px と 780px のウィンドウでナビゲーション・見出し欄・余白を除いた幅。
        val (wideStart, wideSearch) = render(503)
        val (narrowStart, narrowSearch) = render(383)
        assertEquals(wideStart.top, wideSearch.top, 0.5f)
        assertTrue(narrowSearch.top >= narrowStart.bottom)
        assertTrue(narrowSearch.left >= 0 && narrowSearch.right <= 383)
        assertTrue(wideSearch.width > 0)
        assertEquals(wideSearch.width, narrowSearch.width, 0.5f)
    }

    private fun descendants(node: SemanticsNode): Sequence<SemanticsNode> = sequence {
        yield(node)
        for (child in node.children) yieldAll(descendants(child))
    }
}
