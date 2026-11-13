package app.plamotranslate.camera

import android.graphics.Rect
import app.plamotranslate.camera.LineTracker.Track
import app.plamotranslate.overlay.textLine
import app.plamotranslate.pipeline.layout.MenuGrouper
import app.plamotranslate.pipeline.layout.TextGrouper

/** 表示と翻訳の単位。安定した行（[Track]）を1つ以上まとめたもの。 */
internal class Paragraph(val lines: List<Track>) {
    val rect = Rect(lines.first().rect).apply { lines.drop(1).forEach { union(it.rect) } }

    /** 行をつないだ原文。 */
    val text: String = Paragraphs.join(lines.map { it.consensus() })

    /** すべての行の読み取りがそろったか。 */
    val settled: Boolean get() = lines.all { it.agreement() >= LineTracker.AGREE }
}

/**
 * 安定した行を段落にまとめる（docs/設計/カメラ翻訳.md「段落にまとめる」）。読み取りの安定は行ごと、表示と翻訳は段落ごと。
 * まとめ方の規則は画面翻訳と同じ中核の部品（文章：[TextGrouper]、表・メニュー：[MenuGrouper]）を使う。
 */
internal object Paragraphs {
    /** [lines] は上から順に並んだ行。[table] なら表・メニューのまとめ方。 */
    fun group(lines: List<Track>, table: Boolean): List<Paragraph> {
        val grouper = if (table) MenuGrouper else TextGrouper
        return grouper.group(lines.map { textLine(it.consensus(), it.rect) }).map { u -> Paragraph(u.members.map { lines[it] }) }
    }

    /** 行をつなぐ。韓国語・欧文は単語の間で折り返すので空白、日本語・中国語は詰める。 */
    fun join(parts: List<String>): String {
        val trimmed = parts.map { it.trim() }
        val spaced = trimmed.any { p -> p.any { it in '가'..'힣' || it in 'A'..'Z' || it in 'a'..'z' } }
        return trimmed.joinToString(if (spaced) " " else "")
    }
}
