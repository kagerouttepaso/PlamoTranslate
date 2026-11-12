package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.Language

/** 訳す片（文の切れ目で分けた後の1つ）。[single] なら、ほかの片とつながずに1つで訳す（訳し直し）。 */
data class Piece(val text: String, val single: Boolean = false)

/**
 * 翻訳のコア（docs/設計/翻訳コア.md「翻訳の契約」）。訳す片を1回の依頼にまとめ（[plan]）、終わった依頼の訳を片へ戻す（[finish]）。
 * 呼ぶ側（機能）は片の単位で頼み、片の単位で受け取る。行をつなぐ・長文を分ける・訳し直すは、ここに閉じる。
 * 「同時に訳す数」の設定で付け替える（[coreFor]）。
 */
sealed interface TranslationCore {
    /** 同時に訳す依頼の数。 */
    val slots: Int

    /** 呼ぶ側の文（ブロック）を、文の切れ目でおよそこの字数の片に分ける（[TranslationChecks.splitLong]）。 */
    val splitAt: Int

    /** [queue] の先頭から、次の1回の依頼に入れる片を取り出す。[queue] は空でないこと。 */
    fun plan(queue: ArrayDeque<Piece>): List<Piece>

    /** 依頼 [pieces] の訳 [output] を、片ごとの確定（片の原文 → 訳）と、訳し直す片に分ける。 */
    fun finish(pieces: List<Piece>, output: String, target: Language): Outcome

    data class Outcome(val finals: List<Pair<String, String>>, val retry: List<String>)
}

/**
 * 1件ずつ訳すコア。片を [MAX_LINES] 行・[MAX_CHARS] 字まで改行でつないで1回で訳し、訳文を改行で分けて片へ戻す
 * （1件ずつ順に訳すので、1回ごとにかかる原文の前置き（PFML3の見出し）の処理を減らすため。2026-11-05、1片ずつだと1画面で20秒を超えた）。
 * 行数がずれたらつないだ片を全部、行数が合っても原文のまま写した片はその片を、1片ずつ訳し直す。
 */
class SequentialCore : TranslationCore {
    override val slots = 1
    override val splitAt = TranslationChecks.LONG_TEXT

    override fun plan(queue: ArrayDeque<Piece>): List<Piece> {
        val first = queue.removeFirst()
        if (first.single) return listOf(first)
        val chunk = mutableListOf(first)
        var chars = first.text.length
        while (chunk.size < MAX_LINES) {
            val next = queue.firstOrNull() ?: break
            if (next.single || chars + next.text.length > MAX_CHARS) break
            chunk += queue.removeFirst()
            chars += next.text.length
        }
        return chunk
    }

    override fun finish(pieces: List<Piece>, output: String, target: Language): TranslationCore.Outcome {
        if (pieces.size == 1) return TranslationCore.Outcome(listOf(pieces[0].text to output.trim()), emptyList())
        val lines = output.trim().split('\n').map { it.trim() }
        // 行がずれたら、つないだ片を1つずつ訳し直す
        if (lines.size != pieces.size) return TranslationCore.Outcome(emptyList(), pieces.map { it.text })
        val finals = mutableListOf<Pair<String, String>>()
        val retry = mutableListOf<String>()
        pieces.forEachIndexed { k, p ->
            // 行数が合っても、原文のまま写した行は訳し直す（言語の混ざったまとまりで起きる）
            if (TranslationChecks.untranslated(p.text, lines[k], target)) retry += p.text else finals += p.text to lines[k]
        }
        return TranslationCore.Outcome(finals, retry)
    }

    companion object {
        const val MAX_LINES = 8
        const val MAX_CHARS = 400
    }
}

/**
 * 同時に訳すコア。片をつながずに1片を1回で訳す（2026-11-12、作者の判断「並列でできるなら、並列の場合は訳し直しのロジックはなしでいい」）。
 * つなぐと行のずれ・写しで訳し直しが毎回1〜4件出て、その行が遅れた。長い文は文の切れ目で細かく分け（[TranslationChecks.PARALLEL_SPLIT]）、
 * 分けた片をほかの流れでも訳す（Discordのボットの長文が最後に1本だけで訳され、1画面に7〜9秒かかった）。
 */
class ParallelCore(override val slots: Int) : TranslationCore {
    override val splitAt = TranslationChecks.PARALLEL_SPLIT

    override fun plan(queue: ArrayDeque<Piece>): List<Piece> = listOf(queue.removeFirst())

    override fun finish(pieces: List<Piece>, output: String, target: Language) =
        TranslationCore.Outcome(pieces.map { it.text to output.trim() }, emptyList())
}

/** 「同時に訳す数」の設定に合うコア。 */
fun coreFor(parallel: Int): TranslationCore = if (parallel <= 1) SequentialCore() else ParallelCore(parallel)
