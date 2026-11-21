package app.plamotranslate.desktop

import app.plamotranslate.pipeline.conversation.Turn
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal object DesktopConversationLog {
    private val date = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val time = DateTimeFormatter.ofPattern("HH:mm")
    private val timeSeconds = DateTimeFormatter.ofPattern("HH:mm:ss")
    private val fileTime = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

    fun format(turns: List<Turn>, zone: ZoneId = ZoneId.systemDefault()): String = buildString {
        if (turns.isEmpty()) return ""
        val first = Instant.ofEpochMilli(turns.first().timeMillis).atZone(zone)
        val last = Instant.ofEpochMilli(turns.last().timeMillis).atZone(zone)
        val directions = turns.map { it.direction }
        val mine = directions.mapNotNull { if (it.mine) it.source else it.target }.distinct()
        val partners = directions.mapNotNull { if (it.mine) it.target else it.source }.distinct() - mine.toSet()
        append("# 会話ログ ${date.format(first)} ${time.format(first)}〜${time.format(last)}\n\n")
        append("- 言語：${mine.joinToString("・") { it.label }} ⇄ ${partners.joinToString("・") { it.label }.ifEmpty { "（なし）" }}\n")
        append("- 発話：${turns.size}件\n")
        append("- Mockingwordの端末内の音声認識と翻訳による。聞き違い・訳し違いを含むことがある\n")
        for (turn in turns) {
            val whenSpoken = timeSeconds.format(Instant.ofEpochMilli(turn.timeMillis).atZone(zone))
            val direction = turn.direction
            append("\n**$whenSpoken ${if (direction.mine) "自分" else "相手"}（${direction.source.label}）**\n")
            append("原文：${turn.original.oneLine()}\n")
            val target = direction.target
            if (target == null) {
                append("訳：（相手の言語が分からず、訳していない）\n")
            } else {
                val text = when {
                    !turn.translated -> "（翻訳中）"
                    turn.failure != null -> "（翻訳に失敗しました：${turn.failure}）"
                    else -> turn.translation
                }
                append("訳（${target.label}）：${text.oneLine()}\n")
            }
        }
    }

    fun fileName(turns: List<Turn>, zone: ZoneId = ZoneId.systemDefault()): String {
        val start = Instant.ofEpochMilli(turns.first().timeMillis).atZone(zone)
        return "会話ログ_${fileTime.format(start)}.md"
    }

    private fun String.oneLine() = trim().replace(Regex("\\s*\n\\s*"), " ")
}
