package app.plamotranslate.desktop

import app.plamotranslate.desktop.pdf.PdfDocument
import app.plamotranslate.pipeline.pdf.PdfEffect
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfParagraphs
import app.plamotranslate.pipeline.pdf.PdfRead
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.time.TestTimeSource

class DesktopPdfReaderTest {
    @get:Rule val directory = TemporaryFolder()

    @Test fun `表示中の版を保ち発行を待つページも保存できる`() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "amd64")
        val worker = SupervisorJob()
        val reader = DesktopPdfReader(CoroutineScope(Dispatchers.Default + worker), TestTimeSource())
        val source = directory.newFile("sample.pdf")
        PDDocument().use { pdf ->
            for (name in listOf("First example", "Second example")) {
                val page = PDPage().also(pdf::addPage)
                PDPageContentStream(pdf, page).use { content ->
                    content.beginText()
                    content.setFont(PDType1Font(Standard14Fonts.FontName.HELVETICA), 16f)
                    content.newLineAtOffset(40f, 600f)
                    content.showText(name)
                    content.endText()
                }
            }
            pdf.save(source)
        }
        val partialFile = directory.root.toPath().resolve("partial.pdf")
        val completeFile = directory.root.toPath().resolve("complete.pdf")
        val documentId = 1L
        try {
            withTimeout(30_000) {
                reader.read(PdfRead.Open(documentId, source.path))
                assertEquals(2, reader.events.filterIsInstance<PdfEvent.Opened>().first().count)
                val original = checkNotNull(reader.documents.value.original)

                suspend fun write(page: Int, translation: String) {
                    reader.read(PdfRead.Extract(documentId, page))
                    val extracted = reader.events.filterIsInstance<PdfEvent.Extracted>().first()
                    val paragraphs = PdfParagraphs.page(checkNotNull(extracted.pageText))
                    reader.write(PdfEffect.WritePage(documentId, page, paragraphs,
                        mapOf(paragraphs.single().text to translation)))
                    assertTrue(reader.events.filterIsInstance<PdfEvent.Written>().first().success)
                }

                write(0, "一ページ目")
                val first = reader.documents.first { it.writtenPages == setOf(0) }
                assertTrue(first.fonts is PdfFontStatus.Ready)
                val firstVersion = checkNotNull(first.latest)
                checkNotNull(firstVersion.acquire()).use { showing ->
                    reader.save(partialFile, emptyMap())
                    assertTrue(showing.document.search(0, "一ページ目").isNotEmpty())
                    assertTrue(showing.document.search(1, "Second example").isNotEmpty())

                    write(1, "二ページ目")
                    assertSame(firstVersion, reader.documents.value.latest)
                    reader.save(completeFile, emptyMap())
                    assertEquals(setOf(0), reader.documents.value.writtenPages)
                    reader.flush()
                    val second = reader.documents.first { it.writtenPages == setOf(0, 1) }
                    val secondVersion = checkNotNull(second.latest)
                    assertTrue(secondVersion.number > firstVersion.number)
                    checkNotNull(secondVersion.acquire()).use { latest ->
                        assertTrue(latest.document.search(1, "二ページ目").isNotEmpty())
                    }
                    assertTrue(showing.document.search(1, "Second example").isNotEmpty())
                    checkNotNull(original.acquire()).use { base ->
                        assertTrue(base.document.search(0, "First example").isNotEmpty())
                    }
                }
                assertNull(firstVersion.acquire())
                for ((file, query) in listOf(partialFile to "Second example", completeFile to "二ページ目")) {
                    val saved = PdfDocument.open(file)
                    try {
                        assertTrue(saved.search(0, "一ページ目").isNotEmpty())
                        assertTrue(saved.search(1, query).isNotEmpty())
                    } finally {
                        saved.close()
                    }
                }
            }
        } finally {
            worker.cancelAndJoin()
        }
    }
}
