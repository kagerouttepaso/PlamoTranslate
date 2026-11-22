package app.plamotranslate.desktop

import app.plamotranslate.pipeline.pdf.PdfEffect
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfRead
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

@OptIn(ExperimentalCoroutinesApi::class)
class PdfPresenterTest {
    @Test fun `表示中のページの確定訳だけを新しい画像の描画完了まで残す`() = runTest {
        val reader = object : PdfReader {
            override val documents = MutableStateFlow(PdfDocuments())
            override val events = emptyFlow<PdfEvent>()
            override fun read(request: PdfRead) = Unit
            override fun write(request: PdfEffect.WritePage) = Unit
            override fun flush() = Unit
            override suspend fun save(path: Path, titles: Map<String, String>) = error("保存の依頼はない")
        }
        val presenter = PdfPresenter(backgroundScope, reader, { _, _ -> error("翻訳の依頼はない") }, { Path.of("manual.pdf") })
        presenter.choose()
        val document = checkNotNull(presenter.state.value.documents.documentId)
        presenter.visible(document, setOf(0))
        reader.documents.value = PdfDocuments(documentId = document, writtenPages = setOf(0, 1))
        runCurrent()
        assertEquals(setOf(0), presenter.state.value.view.replacing)
        presenter.rendered(document - 1, 0)
        assertTrue(presenter.state.value.view.fading.isEmpty())
        presenter.rendered(document, 0)
        assertEquals(setOf(0), presenter.state.value.view.fading)
        presenter.visible(document, setOf(1))
        assertTrue(presenter.state.value.view.fading.isEmpty())

        presenter.visible(document, setOf(2))
        reader.documents.value = reader.documents.value.copy(writtenPages = setOf(0, 1, 2))
        runCurrent()
        assertEquals(setOf(2), presenter.state.value.view.replacing)
        presenter.showTranslation(false)
        presenter.rendered(document, 2)
        assertTrue(presenter.state.value.view.replacing.isEmpty())
        assertTrue(presenter.state.value.view.fading.isEmpty())
    }
}
