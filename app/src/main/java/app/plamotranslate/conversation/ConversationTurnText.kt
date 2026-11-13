package app.plamotranslate.conversation

import app.plamotranslate.pipeline.conversation.Turn
import java.util.Locale

/*
 * 会話のカード（[Turn]）を画面と会話ログに出す文。中核の [Turn] は数値と理由だけを持ち、文はここで組み立てる。
 */

/** 出す訳文。失敗したら、その知らせ。 */
internal val Turn.shownTranslation: String
    get() = failure?.let { "（翻訳に失敗しました：$it）" } ?: translation

/** かかった時間（認識・発話の長さ・翻訳）。 */
internal val Turn.statsText: String
    get() = buildString {
        append("認識${recognizeMs}ms（発話${String.format(Locale.US, "%.1f", durationMs / 1000.0)}秒）")
        translateMs?.let { append("・翻訳${it}ms") }
    }
