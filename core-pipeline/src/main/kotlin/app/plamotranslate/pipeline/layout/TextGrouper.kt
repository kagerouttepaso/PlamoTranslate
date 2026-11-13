package app.plamotranslate.pipeline.layout

/**
 * 文章の段落にまとめる（チャット・ウェブ・カメラ翻訳の文章のモード。2026-11-06、作者の提案「重なる枠は1段落として統合した方が欠落が無くなる」）。
 *
 * すぐ下に続く行（行間が行の高さの0.8倍以内、横にも重なる、高さが1.6倍以内）は同じ段落にする。
 * 番号・記号で始まる行（[ITEM_START]）は新しい段落にし、段落は [PARAGRAPH_LINES] 行まで。
 * 番号付きの注意書きが1つの巨大な段落になると、訳が遅く、1行の読み揺れで全体を訳し直すため。
 */
object TextGrouper : LineGrouper {
    /** 1段落の行数の上限。 */
    const val PARAGRAPH_LINES = 6

    /**
     * 新しい段落を始める行の頭：「1.」「1)」「1 」（文字認識が点を落とす）「①」「・」「-」「※」「*」など。
     * 「2인분」「500ml」のように数字のすぐ後に文字が続く行は含めない。
     */
    val ITEM_START = Regex("""^(\d{1,2}(?:[.)．]|\s)|[①-⑳]|[・•\-※*▪■□●○◆◇])""")

    override fun group(lines: List<TextLine>): List<List<Int>> {
        val paragraphs = mutableListOf<MutableList<Int>>()
        for (i in lines.indices.sortedBy { lines[it].box.top }) {
            val r = lines[i].box
            val target = if (ITEM_START.containsMatchIn(lines[i].text.trimStart())) null else paragraphs.lastOrNull { p ->
                val last = lines[p.last()].box
                p.size < PARAGRAPH_LINES &&
                    r.left < last.right && r.right > last.left &&
                    r.top - last.bottom <= maxOf(last.height, r.height) * 0.8f &&
                    maxOf(last.height, r.height) <= minOf(last.height, r.height) * 1.6f
            }
            if (target != null) target += i else paragraphs += mutableListOf(i)
        }
        return paragraphs
    }
}
