package app.plamotranslate.pipeline.pdf

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfPresentationRulesTest {
    @Test fun `段落内で文字色から十分離れた最頻色を背景にする`() {
        val rowColor = 0x00eaeaea
        val outside = 0x00ffffff
        val pixels = intArrayOf(
            outside, outside, outside, outside,
            rowColor, 0, 0x00303030, rowColor,
            rowColor, rowColor, 0x00f0f0f0, rowColor,
            outside, outside, outside, outside,
        )
        val background = PdfBackground(pixels, 4, 4, PdfPageSize(4.0, 4.0))
        assertEquals(PdfColor(234, 234, 234), background.color(PdfBounds(0.0, 2.0, 3.0, 3.0), PdfColor.Black))
        assertEquals(PdfColor.White, background.color(PdfBounds(1.0, 3.0, 1.0, 3.0), PdfColor.Black))
        val tie = PdfBackground(intArrayOf(0x00800000, 0x00008000), 2, 1, PdfPageSize(2.0, 1.0))
        assertEquals(PdfColor(128, 0, 0), tie.color(PdfBounds(0.0, 0.0, 1.0, 1.0), PdfColor.Black))
        // 画素の範囲で渡しても同じ規則で選び、画像の外へはみ出す範囲は画像の内側に収める。
        assertEquals(PdfColor(234, 234, 234), background.color(0, 1, 3, 2, PdfColor.Black))
        assertEquals(PdfColor(234, 234, 234), background.color(-5, 1, 99, 2, PdfColor.Black))
    }

    @Test fun `しおりはタグ空白大文字小文字を無視し書いた本文の訳文を使う`() {
        val paragraph = testParagraphs(TestLine("Power Supply")).single()
        assertEquals("power supply", PdfTitles.titleKey(" <a1> POWER </a1>　Supply  "))
        val translations = PdfTitles.titleTranslations(listOf(paragraph), mapOf(paragraph.text to " 電源　装置 "))
        assertEquals("電源 装置", translations[PdfTitles.titleKey("POWER   SUPPLY")])
        assertTrue(PdfTitles.titleTranslations(listOf(paragraph), mapOf(paragraph.text to "Power Supply")).isEmpty())
    }

    @Test fun `途中訳は文末までに切り確定した行のラベルは句点が無くても表示する`() {
        assertEquals("設定しました。", PdfPreview.text("設定しました。 次を", complete = false))
        assertEquals("設定しました。", PdfPreview.text("設定しました。次を", complete = false))
        assertEquals("<a1>設定しました。</a1>", PdfPreview.text("<a1>設定しました。</a1> 次を", complete = false))
        assertEquals("", PdfPreview.text("電源入力", complete = false))
        assertEquals("電源入力", PdfPreview.text("電源入力<a", complete = true))
        assertEquals("", PdfPreview.text("<c1>定数", complete = true))
    }

    @Test fun `小数と閉じ括弧とアラビア語の疑問符で文末を区別する`() {
        for ((line, expected) in listOf(
            "「1.23です。」次の文" to "「1.23です。」",
            "The value is 1.23. Next" to "The value is 1.23.",
            "أين الملف؟ الجملة التالية" to "أين الملف؟",
        )) assertEquals(PdfPreviewLine(expected, 1, false), PdfPreview.line(line, complete = false))
    }

    @Test fun `同じ原文の途中訳と確定訳は出現箇所ごとの書式で表示する`() {
        val first = testParagraphs(TestLine("First. Second sentence.")).single()
        val second = first.copy(bold = true, color = PdfColor(1, 2, 3))
        val state = PdfState(layouts = mapOf(0 to listOf(first), 1 to listOf(second)), working = setOf(first.text),
            previews = mapOf(first.text to PdfPreview.line("最初です。次", false)))
        assertEquals(listOf(false, true), (0..1).map { state.pageMarks(it).single().pieces.first().bold })
        assertEquals(PdfColor(1, 2, 3), state.pageMarks(1).single().paragraph.color)
        assertTrue(state.pageMarks(1).single().pieces.last().source)
        val confirmed = state.copy(translations = mapOf(1 to mapOf(0 to "確定した訳文。")), confirmedMarks = setOf(first.text))
        assertEquals(listOf(PdfPiece("確定した訳文。", bold = true)), confirmed.pageMarks(1).single().pieces)
    }

    @Test fun `途中表示では対応する文まで原文を落とし残りを後ろに置く`() {
        val paragraph = testParagraphs(TestLine("First sentence. Remaining source.")).single()
        val preview = PdfPreview.line("最初の文です。次の", complete = false)
        assertEquals(listOf(PdfPiece("最初の文です。"), PdfPiece(" ", source = true),
            PdfPiece("Remaining source.", source = true)), PdfMarkupTranslation.previewPieces(paragraph, preview))
        assertEquals(listOf(PdfPiece("ラベル")), PdfMarkupTranslation.previewPieces(paragraph, PdfPreview.line("ラベル", complete = true)))
    }
}
