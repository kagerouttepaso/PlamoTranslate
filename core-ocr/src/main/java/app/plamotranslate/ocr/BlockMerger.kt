package app.plamotranslate.ocr

import android.graphics.Rect

/**
 * 1つの読み手が返した1行（縦書きなら1列）。[bounds] は撮影画像の座標。
 * [source] と [confidence] は読み手から来た行だけが持つ（束ね直すときの行は持たない）。
 */
internal data class Candidate(
    val text: String,
    val bounds: Rect,
    val source: Source? = null,
    val confidence: Float? = null,
)

/**
 * 読み手ごとの行を、行ごとに文字種に合うものを選んで1つにする（docs/設計/画面翻訳.md「行の選び方とまとめ方」）。
 *
 * 処理の順番：
 * 1. 縦書きの行を先に採る。縦書きを読める読み手（[Source.readsVertical]）があればその行を採り、無ければ ML Kit 中国語の
 *    1文字ずつの位置から列を組み直す（[VerticalColumns]）。その範囲に重なるほかの行（縦書きを横に読み違えた行）は捨てる。
 * 2. 残りの行を、位置が重なるものどうしで突き合わせ（[LineGroup]）、[LineChoice] に1つ選ばせる。
 * 3. 選んだ行を読み順に並べる。吹き出し・段落にまとめるのは、読む工程の後（中核の `LineGrouper`）。
 *
 * 段落ごとに選ぶと、日本語の解説に韓国語の引用が混ざった段落で、片方の言語が崩れた文字列になった
 * （2026-11-05実機）。そのため行ごとに選ぶ。
 *
 * 読み手の能力は [Source]、採る方針（確信度の下限・優先順）は [ChoicePolicy] が持ち、ここでは読み手の名前で分岐しない。
 */
internal object BlockMerger {
    private const val SAME_LINE_IOU = 0.4f

    /** 同じ行とみなす上端のずれ（px）。読み順を上から下、左から右にそろえる。 */
    private const val ROW_TOLERANCE_PX = 24

    /** 同じ位置の行を、読み手ごとに読んだ版。 */
    private class LineGroup(val bounds: Rect) {
        val versions = mutableMapOf<Source, Candidate>()

        fun pick(): Candidate {
            val readings = versions.map { (source, c) -> c.reading(source) }
            return versions.getValue(LineChoice.choose(readings).source)
        }
    }

    /**
     * [read] は読み手ごとの行。動かさなかった読み手は入れない。採ってよい行（[ChoicePolicy.accepts]）だけをここで残す。
     * [glyphs] は1文字ずつの位置（[Source.buildsColumns] の読み手のもの）で、縦書きを読める読み手が無いときに列を組み直すのに使う。
     */
    fun merge(
        read: Map<Source, List<Candidate>>,
        glyphs: List<Glyph> = emptyList(),
    ): List<OcrLine> {
        val lines = read.mapValues { (source, list) -> list.filter { ChoicePolicy.accepts(source, it.text, it.confidence) } }
        // 縦書きを読める読み手の行を先に採る。確信度が低く、韓国語の行と重なる範囲は韓国語の行に譲る
        val koreanLines = lines.flatMap { (source, list) -> list.filter { ChoicePolicy.isKoreanLine(it.reading(source)) } }
        val verticalSources = lines.keys.filter { it.readsVertical }
        val claimed = verticalSources.associateWith { source ->
            lines.getValue(source).filter { v ->
                !ChoicePolicy.yieldsToKorean(v.reading(source)) || koreanLines.none { overlaps(it.bounds, v.bounds) }
            }
        }
        // 縦書きを読める読み手が無いときだけ、1文字ずつの位置から縦書きの列を組み直す
        val columnSource = Source.entries.firstOrNull { it.buildsColumns && it in lines }
        val columns = if (verticalSources.isEmpty() && columnSource != null) {
            VerticalColumns.build(glyphs).map { it.copy(source = columnSource) }
        } else {
            emptyList()
        }
        val covered = claimed.values.flatten() + columns
        // 採用した行の範囲に重なるほかの行（縦書きを横に読み違えた行など）は捨てる
        fun outside(list: List<Candidate>) = list.filter { c -> covered.none { overlaps(c.bounds, it.bounds) } }

        val groups = mutableListOf<LineGroup>()
        for (source in Source.entries) {
            val list = when {
                source in claimed -> claimed.getValue(source)
                source == columnSource -> columns + outside(lines[source].orEmpty())
                else -> outside(lines[source].orEmpty())
            }
            for (c in list) {
                val tagged = if (c.source == source) c else c.copy(source = source)
                val group = groups.firstOrNull { Geometry.iou(it.bounds, tagged.bounds) >= SAME_LINE_IOU }
                if (group == null) {
                    groups += LineGroup(Rect(tagged.bounds)).also { it.versions[source] = tagged }
                } else if (source !in group.versions) {
                    group.versions[source] = tagged
                }
            }
        }

        return groups.map { it.pick() }
            .filter { it.text.isNotBlank() }
            .map { OcrLine(it.text, it.bounds, ScriptDetector.detect(it.text)) }
            .sortedWith(compareBy({ it.bounds.top / ROW_TOLERANCE_PX }, { it.bounds.left }))
    }

    private fun Candidate.reading(source: Source) = Reading(source, text, confidence)

    /** どちらかの面積の3割以上が重なるか。 */
    private fun overlaps(a: Rect, b: Rect): Boolean = Geometry.overlaps(a, b, 0.3f)
}
