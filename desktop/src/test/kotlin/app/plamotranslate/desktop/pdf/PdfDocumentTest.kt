package app.plamotranslate.desktop.pdf

import app.plamotranslate.pipeline.pdf.PdfBounds
import app.plamotranslate.pipeline.pdf.PdfColor
import app.plamotranslate.pipeline.pdf.PdfParagraphs
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSDictionary
import org.apache.pdfbox.cos.COSInteger
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSStream
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDFormContentStream
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.PDResources
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.encryption.AccessPermission
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.PDType3Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import org.apache.pdfbox.util.Matrix
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.Color
import java.io.ByteArrayOutputStream

class PdfDocumentTest {
    @get:Rule val directory = TemporaryFolder()

    @Before fun windows() {
        assumeTrue(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") == "amd64")
    }

    @Test fun `Unicode に対応しない字形は原文のフォントで訳文内へ移す`() = runBlocking {
        val source = unmappedFontFixture()
        val fonts = PdfFonts.load()
        val magenta = Color.MAGENTA.rgb
        withPdf(source) { pdf ->
            val page = pdf.pageText(0)
            val original = page.glyphs.filter { it.unmappedAdvance != null }
            assertEquals(2, original.size)
            val oldPixel = pdf.render(0, 300, 300).indexOf(magenta)
            assertTrue(oldPixel >= 0)
            val paragraphs = PdfParagraphs.page(page)
            val paragraph = paragraphs.single()
            assertTrue(paragraph.text.contains("<c1>…</c1>"))
            assertFalse(paragraph.text.contains('\uFFFD'))
            val missing = pdf.writePage(0, paragraphs, mapOf(paragraph.text to "設定を確認します。"), fonts)
            assertEquals(0, missing.translated)
            val duplicate = pdf.writePage(0, paragraphs, mapOf(paragraph.text to "設定 <c1>x</c1><c1>y</c1>"), fonts)
            assertEquals(0, duplicate.translated)
            val write = pdf.writePage(0, paragraphs, mapOf(paragraph.text to "設定 <c1>無視</c1> を確認します。"), fonts)
            assertEquals(1, write.translated)
            val snapshot = pdf.snapshot()
            withPdf(snapshot.bytes) { live ->
                withPdf(snapshot.finish().bytes) { saved ->
                    val image = saved.render(0, 300, 300)
                    assertArrayEquals(live.render(0, 300, 300), image)
                    val newPixel = image.indexOf(magenta)
                    assertTrue("元の字形が訳文の位置に描かれる: $oldPixel → $newPixel", newPixel >= 0 && newPixel != oldPixel)
                    val japanese = saved.search(0, "設定").single()
                    assertTrue(newPixel % 300 > japanese.boxes.maxOf { it.right })
                    assertTrue(saved.search(0, "無視").isEmpty())
                    val glyphColumns = image.indices.filter { image[it] == magenta }.map { it % 300 }.toSortedSet()
                    assertEquals(2, glyphColumns.zipWithNext().count { (left, right) -> right > left + 1 } + 1)
                }
            }
        }
    }

    @Test fun `文字の座標と書式から検索および選択ができる`() = runBlocking {
        val file = directory.newFile("日本語.pdf").apply { writeBytes(fixture()) }
        withPdf(PdfDocument.open(file.toPath())) { pdf ->
            assertEquals(2, pdf.pageCount)
            assertFalse(pdf.encrypted)
            val page = pdf.pageText(0)
            val welcome = page.glyphs.first { it.text == "W" }
            assertEquals(240.0, welcome.originY, 0.001)
            assertEquals(15.0, welcome.size, 0.001)
            assertEquals(PdfColor(20, 40, 80), welcome.color)
            assertEquals(0, welcome.link)
            assertTrue(welcome.font.contains("Helvetica"))
            assertTrue(welcome.weight >= 600 || welcome.font.contains("Bold"))
            assertTrue(welcome.bounds.width > 0)
            assertEquals(listOf(0), welcome.objectPath)

            val hit = pdf.search(0, "welcome").single()
            assertEquals(welcome.index, hit.start)
            assertTrue(hit.boxes.isNotEmpty())
            assertEquals("Welcome", pdf.selection(0, hit.start, hit.length).text)
            assertEquals(hit.boxes, pdf.selection(0, hit.start, hit.length).boxes)
            assertEquals(welcome.index, pdf.characterAt(0,
                (welcome.bounds.left + welcome.bounds.right) / 2, (welcome.bounds.bottom + welcome.bounds.top) / 2))
            val view = pdf.viewport(0)
            val point = view.toView(welcome.originX, welcome.originY)
            assertEquals(0.2, point.y, 0.001)
            assertEquals(welcome.originY, view.toPage(point.x, point.y).y, 0.001)

            val target = PdfDestination.Page(1, 22.0, 195.0, null)
            val outline = pdf.outline().single()
            assertEquals("Welcome to the manual", outline.title)
            assertEquals(target, outline.destination)
            assertEquals("Source only", outline.children.single().title)
            assertEquals(target, pdf.links(0).first().destination)
            assertEquals(PdfDestination.Uri("https://example.com/manual"), pdf.links(0).last().destination)
        }
    }

    @Test fun `書き換えたページとフォントを縮めた保存結果を同じように描画できる`() = runBlocking {
        val fonts = PdfFonts.load()
        val source = fixture()
        lateinit var progress: PdfSnapshot
        lateinit var snapshot: PdfSnapshot
        val translation = "日本語の取扱説明書へようこそ。設定の手順を確認してください。"
        withPdf(source) { pdf ->
            val paragraphs = PdfParagraphs.page(pdf.pageText(0))
            val welcome = paragraphs.single { it.plain.startsWith("Welcome") }
            val other = paragraphs.single { it.plain == "Fixed serial label" }
            val before = pdf.snapshot()
            assertEquals(0, pdf.writePage(0, paragraphs, mapOf(other.text to other.plain), fonts).translated)
            assertTrue(embeddedLengths(before.bytes).isEmpty())
            assertTrue(fonts.measure("日本語", true, 14.0) > 0)
            val write = pdf.writePage(0, paragraphs, mapOf(welcome.text to translation, other.text to other.plain), fonts)
            assertEquals(PdfWriteResult(1, 0, 1), write)
            progress = pdf.snapshot()
            val second = PdfParagraphs.page(pdf.pageText(1))
            assertEquals(1, pdf.writePage(1, second, mapOf(second.single().text to "別のページ"), fonts).translated)
            snapshot = pdf.snapshot()
        }

        val finished = snapshot.finish(mapOf("  WELCOME to the manual  " to "取扱説明書"))
        assertEquals(1, finished.translatedBookmarks)
        val lengths = embeddedLengths(snapshot.bytes)
        assertEquals(listOf(fonts.bold.size, fonts.regular.size).sorted(), lengths)
        val subsets = embeddedLengths(finished.bytes)
        assertEquals(2, subsets.size)
        assertTrue(subsets.all { it in 1 until fonts.bold.size })
        assertTrue(finished.bytes.size < snapshot.bytes.size / 2)
        Loader.loadPDF(snapshot.bytes).use { assertFalse(it.document.trailer.containsKey(COSName.PREV)) }

        withPdf(progress.finish().bytes) { partial ->
            assertTrue(partial.search(0, "日本語").isNotEmpty())
            assertTrue(partial.search(1, "Second example page").isNotEmpty())
            assertTrue(partial.search(1, "別のページ").isEmpty())
        }

        withPdf(snapshot.bytes) { displayed ->
            val page = displayed.pageText(0)
            val bullet = page.glyphs.single { it.text == "•" }
            assertEquals(PdfColor(20, 40, 80), bullet.color)
            assertEquals(15.0, bullet.size, 0.001)
            assertEquals(240.0, bullet.originY, 0.001)
            assertTrue(displayed.search(0, "Welcome").isEmpty())
            assertTrue(displayed.search(0, "Fixed serial label").isNotEmpty())
            assertTrue(displayed.search(0, "日本語").isNotEmpty())
            assertTrue(displayed.search(1, "別のページ").isNotEmpty())
            assertEquals("Welcome to the manual", displayed.outline().single().title)
            val links = displayed.links(0)
            assertEquals(2, links.size)
            assertTrue(links.first().quads.size > 1)
            assertEquals(links.first().quads.reduce(PdfBounds::union), links.first().bounds)
            assertEquals(PdfDestination.Page(1, 22.0, 195.0, null), links.first().destination)
            assertEquals(PdfDestination.Uri("https://example.com/manual"), links.last().destination)

            withPdf(finished.bytes) { saved ->
                val root = saved.outline().single()
                assertEquals("取扱説明書", root.title)
                assertEquals("Source only", root.children.single().title)
                assertEquals(links, saved.links(0))
                assertEquals(displayed.search(0, "日本語"), saved.search(0, "日本語"))
                assertArrayEquals(displayed.render(0, 480, 480), saved.render(0, 480, 480))
                assertArrayEquals(displayed.render(1, 480, 480), saved.render(1, 480, 480))
            }
        }
    }

    @Test fun `フォームの原文を背景色で覆い暗号化と原文のしおりを保つ`() = runBlocking {
        val fonts = PdfFonts.load()
        withPdf(fixture(encrypted = true, withForm = true)) { pdf ->
            assertTrue(pdf.encrypted)
            val before = pdf.render(0, 300, 300)
            val paragraphs = PdfParagraphs.page(pdf.pageText(0))
            val form = paragraphs.single { it.plain == "Form notice" }
            assertTrue(form.objectPaths.all { it.size == 2 })
            assertEquals(PdfColor(220, 235, 245), pdf.backgroundColors(0, listOf(form))[form])
            assertEquals(PdfWriteResult(1, 1, 0), pdf.writePage(0, paragraphs, mapOf(form.text to "ご案内"), fonts))
            val snapshot = pdf.snapshot()
            val output = snapshot.finish(mapOf("Welcome to the manual" to "取扱説明書"))
            assertTrue(output.encrypted)
            assertEquals(0, output.translatedBookmarks)
            assertArrayEquals(snapshot.bytes, output.bytes)
            Loader.loadPDF(output.bytes).use {
                assertTrue(it.isEncrypted)
                assertFalse(it.currentAccessPermission.canModify())
            }
            assertTrue(embeddedLengths(output.bytes).contains(fonts.regular.size))
            withPdf(output.bytes) { saved ->
                assertEquals("Welcome to the manual", saved.outline().single().title)
                assertTrue(saved.search(0, "ご案内").isNotEmpty())
                assertTrue(saved.search(0, "Form notice").isNotEmpty())
                assertFalse(before.contentEquals(saved.render(0, 300, 300)))
            }
        }
    }

    private suspend fun withPdf(bytes: ByteArray, action: suspend (PdfDocument) -> Unit) {
        withPdf(PdfDocument.open(bytes), action)
    }

    private suspend fun withPdf(document: PdfDocument, action: suspend (PdfDocument) -> Unit) {
        try {
            action(document)
        } finally {
            document.close()
        }
    }

    private fun embeddedLengths(bytes: ByteArray): List<Int> = Loader.loadPDF(bytes).use { pdf ->
        pdf.document.getObjectsByType(COSName.FONT_DESC).mapNotNull {
            val descriptor = it.`object` as? COSDictionary
            val stream = descriptor?.getDictionaryObject(COSName.FONT_FILE2) as? COSStream
            stream?.getInt(COSName.LENGTH1)
        }.sorted()
    }

    private fun unmappedFontFixture(): ByteArray = PDDocument().use { pdf ->
        fun stream(text: String) = PDStream(pdf).also { data ->
            data.createOutputStream().use { it.write(text.toByteArray(Charsets.US_ASCII)) }
        }
        val names = listOf(COSName.getPDFName("unknownOne"), COSName.getPDFName("unknownTwo"))
        val differences = COSArray().apply {
            add(COSInteger.get(65))
            names.forEach { add(it) }
        }
        val font = PDType3Font(COSDictionary().apply {
            setItem(COSName.TYPE, COSName.FONT)
            setItem(COSName.SUBTYPE, COSName.TYPE3)
            setItem(COSName.FONT_BBOX, PDRectangle(600f, 800f).cosArray)
            setItem(COSName.FONT_MATRIX, COSArray().apply { setFloatArray(floatArrayOf(0.001f, 0f, 0f, 0.001f, 0f, 0f)) })
            setInt(COSName.FIRST_CHAR, 65)
            setInt(COSName.LAST_CHAR, 66)
            setItem(COSName.WIDTHS, COSArray().apply { setFloatArray(floatArrayOf(600f, 600f)) })
            setItem(COSName.ENCODING, COSDictionary().apply { setItem(COSName.DIFFERENCES, differences) })
            setItem(COSName.CHAR_PROCS, COSDictionary().apply {
                setItem(names[0], stream("600 0 0 0 600 800 d1 0 0 200 800 re f"))
                setItem(names[1], stream("600 0 0 0 600 800 d1 0 0 500 350 re f"))
            })
        })
        val page = PDPage(PDRectangle(300f, 300f)).also(pdf::addPage)
        page.resources = PDResources()
        val regular = page.resources.add(PDType1Font(Standard14Fonts.FontName.HELVETICA)).name
        val opaque = page.resources.add(font).name
        page.setContents(stream(
            "BT /$regular 12 Tf 30 240 Td (Please select ) Tj ET\n" +
                "BT /$opaque 12 Tf 105 240 Td 1 0 1 rg <4142> Tj ET\n" +
                "BT /$regular 12 Tf 124 240 Td 0 g (to continue.) Tj ET\n",
        ))
        ByteArrayOutputStream().also(pdf::save).toByteArray()
    }

    private fun fixture(encrypted: Boolean = false, withForm: Boolean = false): ByteArray = PDDocument().use { pdf ->
        val page = PDPage(PDRectangle(300f, 300f)).also(pdf::addPage)
        val target = PDPage(PDRectangle(300f, 300f)).also(pdf::addPage)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        PDPageContentStream(pdf, page).use {
            it.beginText()
            it.setNonStrokingColor(Color(20, 40, 80))
            it.setFont(bold, 20f)
            it.setTextMatrix(Matrix(0.75f, 0f, 0f, 0.75f, 30f, 240f))
            it.showText("• Welcome to the manual")
            it.endText()
            it.beginText()
            it.setFont(regular, 12f)
            it.newLineAtOffset(30f, 145f)
            it.showText("Fixed serial label")
            it.endText()
            if (withForm) {
                it.setNonStrokingColor(Color(220, 235, 245))
                it.addRect(20f, 50f, 210f, 42f)
                it.fill()
                val form = PDFormXObject(pdf).apply {
                    resources = PDResources()
                    bBox = PDRectangle(300f, 300f)
                }
                PDFormContentStream(form).use { formContent ->
                    formContent.beginText()
                    formContent.setNonStrokingColor(Color.BLACK)
                    formContent.setFont(regular, 14f)
                    formContent.newLineAtOffset(30f, 65f)
                    formContent.showText("Form notice")
                    formContent.endText()
                }
                it.drawForm(form)
            }
        }
        PDPageContentStream(pdf, target).use {
            it.beginText()
            it.setFont(regular, 12f)
            it.newLineAtOffset(30f, 220f)
            it.showText("Second example page")
            it.endText()
        }
        val destination = PDPageXYZDestination().also {
            it.page = target
            it.left = 22
            it.top = 195
        }
        fun link(rect: PDRectangle) = PDAnnotationLink().also { it.rectangle = rect }
        page.annotations = listOf(
            link(PDRectangle(39f, 236f, 171f, 20f)).also { it.destination = destination },
            link(PDRectangle(51f, 238f, 30f, 14f)).also { it.destination = destination },
            link(PDRectangle(30f, 141f, 80f, 14f)).also {
                it.action = PDActionURI().also { action -> action.uri = "https://example.com/manual" }
            },
        )
        val outline = PDDocumentOutline().also { pdf.documentCatalog.documentOutline = it }
        val root = PDOutlineItem().apply { title = "Welcome to the manual"; setDestination(destination) }
        root.addLast(PDOutlineItem().apply { title = "Source only"; setDestination(page) })
        outline.addLast(root)
        if (encrypted) {
            val permissions = AccessPermission().apply { setCanModify(false) }
            pdf.protect(StandardProtectionPolicy("fixture-owner", "", permissions).apply { encryptionKeyLength = 128 })
        }
        ByteArrayOutputStream().also(pdf::save).toByteArray()
    }
}
