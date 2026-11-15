package app.plamotranslate.conversation

import app.plamotranslate.pipeline.conversation.Turn
import java.util.Locale

/*
 * 会話のカード（[Turn]）を画面と会話ログに出す文。中核の [Turn] は数値と理由だけを持ち、文はここで組み立てる。
 */

/** 出す訳文。失敗したら、その知らせ。相手の言語が分かる前の自分の発話は、相手が分かったら訳すと知らせる。 */
internal val Turn.shownTranslation: String
    get() = failure?.let { "（翻訳に失敗しました：$it）" }
        ?: if (direction.target == null) "（相手の言語が分かったら訳します）" else translation

/** かかった時間（認識・発話の長さ・翻訳）。 */
internal val Turn.statsText: String
    get() = buildString {
        append("認識${recognizeMs}ms（発話${String.format(Locale.US, "%.1f", durationMs / 1000.0)}秒）")
        translateMs?.let { append("・翻訳${it}ms") }
    }
