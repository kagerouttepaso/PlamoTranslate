package app.plamotranslate.pipeline.pdf

// ECMAScript の空白（PDF の既存のマーカーと折り返しの規則に合わせる）。
internal const val pdfWhitespaceInner = "\\t-\\r \\u00a0\\u1680\\u2000-\\u200a\\u2028\\u2029\\u202f\\u205f\\u3000\\ufeff"
internal const val pdfWhitespace = "[$pdfWhitespaceInner]"
internal val pdfMarkTag = Regex("""<(/?)([acv])([0-9]+)/?>""")
private val spaces = Regex("$pdfWhitespace+")
private val edgeSpaces = Regex("^$pdfWhitespace+|$pdfWhitespace+$")
private val anyTag = Regex("<[^>]+>")

internal fun normalizePdfText(text: String) = text.replace(spaces, " ").trim(' ')
internal fun plainPdfText(text: String) = normalizePdfText(text.replace(anyTag, ""))

internal data class PdfMarkup(val text: String, val plain: String, val elements: List<PdfElement>, val whole: PdfElement?)

private val mono = Regex("mono|courier|typewriter|cmtt|consol", RegexOption.IGNORE_CASE)
private val leadingInMarker = Regex("<(a[0-9]+)> ")
private val trailingInMarker = Regex(" </(a[0-9]+)>")

private data class Style(val kind: PdfElementKind, val link: Int?, val bold: Boolean, val unmapped: Boolean = false)

internal fun encodePdfMarkup(glyphs: List<PdfGlyph?>, baseBold: Boolean): PdfMarkup {
    val text = StringBuilder()
    val plain = StringBuilder()
    val code = StringBuilder()
    val unmapped = mutableListOf<PdfGlyph>()
    val elements = mutableListOf<PdfElement>()
    var style: Style? = null
    fun close() {
        val active = style ?: return
        val element = elements.last()
        if (active.kind == PdfElementKind.Code) {
            val original = if (active.unmapped) PdfOriginalText(unmapped.toList()) else null
            val source = if (original == null) code.toString().replace(edgeSpaces, "") else "…"
            elements[elements.lastIndex] = element.copy(code = source, original = original)
            text.append("<c").append(element.id).append('>').append(normalizePdfText(source))
                .append("</c").append(element.id).append('>')
            code.clear()
            unmapped.clear()
        } else {
            text.append("</a").append(element.id).append('>')
        }
    }
    for (glyph in glyphs) {
        val ch = glyph?.text ?: " "
        val next = when {
            glyph == null || glyph.generated || ch == " " -> style?.takeUnless { it.unmapped }
            glyph.unmappedAdvance != null -> Style(PdfElementKind.Code, glyph.link, false, unmapped = true)
            mono.containsMatchIn(glyph.font) -> Style(PdfElementKind.Code, glyph.link, false)
            else -> with(PdfParagraphs) {
                val bold = glyph.isBold() != baseBold
                if (bold || glyph.link != null) Style(PdfElementKind.Decoration, glyph.link, bold) else null
            }
        }
        if (next != style) {
            close()
            style = next
            if (next != null) {
                val id = elements.size + 1
                elements += PdfElement(id, next.kind, next.link, next.bold)
                if (next.kind == PdfElementKind.Decoration) text.append("<a").append(id).append('>')
            }
        }
        when {
            style?.unmapped == true -> unmapped += checkNotNull(glyph)
            style?.kind == PdfElementKind.Code -> code.append(ch)
            else -> {
                text.append(ch)
                plain.append(ch)
            }
        }
    }
    close()
    val raw = normalizePdfText(text.toString())
        .replace(leadingInMarker) { " <${it.groupValues[1]}>" }
        .replace(trailingInMarker) { "</${it.groupValues[1]}> " }
    val marked = normalizePdfText(raw)
    val prose = normalizePdfText(plain.toString())
    val whole = elements.singleOrNull()?.takeIf { it.kind == PdfElementKind.Decoration && marked == "<a1>$prose</a1>" }
    return if (whole == null) PdfMarkup(marked, prose, elements, null) else PdfMarkup(prose, prose, emptyList(), whole)
}

private val token = Regex("""<c([0-9]+)>[\s\S]*?</c\1>|<(/?)([av])([0-9]+)/?>""")

private sealed interface Node {
    data class Text(val text: String) : Node
    data class Code(val element: PdfElement) : Node
    data class Wrap(val element: PdfElement, val children: MutableList<Node> = mutableListOf()) : Node
}

/** 訳が原文と同じかを調べ、書き換える段落だけに書式付きの訳文を作る。 */
object PdfMarkupTranslation {
    fun shouldReplace(paragraph: PdfParagraph, translation: String): Boolean =
        paragraph.skip == null && translation.isNotBlank() && plainPdfText(translation) != plainPdfText(paragraph.plain)

    fun prepare(paragraph: PdfParagraph, translation: String): List<PdfPiece>? {
        if (!shouldReplace(paragraph, translation)) return null
        val pieces = pieces(paragraph, translation)
        if (paragraph.elements.mapNotNull { it.original }.any { original ->
                pieces.count { it.original == original } != 1
            }) return null
        return pieces
    }

    fun pieces(paragraph: PdfParagraph, text: String, source: Boolean = false): List<PdfPiece> =
        styledPieces(paragraph, text, source).map { it.piece }

    /** 訳文の文数だけ原文を落とし、残りを薄く描画する断片として続ける。 */
    fun previewPieces(paragraph: PdfParagraph, preview: PdfPreviewLine): List<PdfPiece> {
        val translation = pieces(paragraph, preview.text)
        if (translation.isEmpty() || preview.complete) return translation
        val source = styledPieces(paragraph, paragraph.text, source = true)
        val prose = source.joinToString("") { it.prose }
        val ends = PdfPreview.sentenceEnds(prose)
        var skip = ends.getOrNull(minOf(preview.sentences, ends.size) - 1) ?: 0
        while (skip > 0 && prose.getOrNull(skip)?.isWhitespace() == true) skip++
        val tail = buildList {
            for (part in source) {
                if (skip == 0) add(part.piece)
                else if (skip < part.prose.length) add(part.piece.copy(text = part.piece.text.drop(skip)))
                skip = (skip - part.prose.length).coerceAtLeast(0)
            }
        }
        return if (tail.isEmpty()) translation else translation + PdfPiece(" ", paragraph.bold, source = true) + tail
    }

    // コードは原文の文末判定で1文字分の要素として扱う。
    private data class StyledPiece(val piece: PdfPiece, val prose: String = piece.text)

    private fun styledPieces(paragraph: PdfParagraph, text: String, source: Boolean): List<StyledPiece> {
        val root = mutableListOf<Node>()
        val stack = mutableListOf<Node.Wrap>()
        fun children() = stack.lastOrNull()?.children ?: root
        fun addText(part: String) {
            val plain = part.replace(pdfMarkTag, "")
            if (plain.isNotEmpty()) children() += Node.Text(plain)
        }
        fun unwrap() {
            val node = stack.removeLast()
            val parent = children()
            val index = parent.indexOf(node)
            parent.removeAt(index)
            parent.addAll(index, node.children)
        }

        var cursor = 0
        for (match in token.findAll(text)) {
            addText(text.substring(cursor, match.range.first))
            cursor = match.range.last + 1
            val code = match.groupValues[1].isNotEmpty()
            val id = match.groupValues[if (code) 1 else 4].toIntOrNull()
            val kind = if (code) PdfElementKind.Code else PdfElementKind.Decoration
            val element = id?.let { paragraph.elements.getOrNull(it - 1) }
            if (element?.kind != kind || match.groupValues[3] == "v") {
                if (code) addText(match.value)
                continue
            }
            when {
                code -> children() += Node.Code(element)
                match.groupValues[2].isEmpty() -> {
                    val wrap = Node.Wrap(element)
                    children() += wrap
                    stack += wrap
                }
                else -> {
                    val index = stack.indexOfLast { it.element.id == id }
                    if (index >= 0) {
                        while (stack.lastIndex > index) unwrap()
                        stack.removeLast()
                    }
                }
            }
        }
        addText(text.substring(cursor))
        while (stack.isNotEmpty()) unwrap()
        return buildList {
            fun addNodes(nodes: List<Node>, bold: Boolean, link: Int?) {
                for (node in nodes) when (node) {
                    is Node.Text -> add(StyledPiece(PdfPiece(node.text, bold, link, source)))
                    is Node.Code -> add(StyledPiece(
                        PdfPiece(node.element.code, bold, node.element.link ?: link, source, node.element.original), "\uFFFC",
                    ))
                    is Node.Wrap -> addNodes(node.children, bold xor node.element.bold, node.element.link ?: link)
                }
            }
            addNodes(root, paragraph.bold, paragraph.whole?.link)
        }
    }
}
