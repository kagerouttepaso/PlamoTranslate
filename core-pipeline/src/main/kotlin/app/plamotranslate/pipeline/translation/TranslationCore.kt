package app.plamotranslate.pipeline.translation

import app.plamotranslate.llm.Language

/** 訳すチャンク（文の切れ目で分けた後の1つ）。[single] なら、ほかのチャンクとつながずに1つで訳す（訳し直し）。 */
data class Piece(val text: String, val single: Boolean = false, val batch: Long? = null)

/**
 * 翻訳のコア（docs/設計/翻訳コア.md「翻訳の契約」）。訳すチャンクを1回の依頼にまとめ（[plan]）、終わった依頼の訳をチャンクへ戻す（[finish]）。
 * 呼ぶ側（機能）はチャンクの単位で頼み、チャンクの単位で受け取る。行をつなぐ・長文を分ける・訳し直すは、ここに閉じる。
 * 「同時に訳す数」の設定で付け替える（[coreFor]）。
 */
sealed interface TranslationCore {
    /** 同時に訳す依頼の数。 */
    val slots: Int

    /** 呼ぶ側の文（ブロック）を、文の切れ目でおよそこの字数のチャンクに分ける（[TranslationChecks.splitLong]）。 */
    val splitAt: Int

    /** [queue] の先頭から、次の1回の依頼に入れるチャンクを取り出す。[queue] は空でないこと。 */
    fun plan(queue: ArrayDeque<Piece>): List<Piece>

    /** 依頼 [pieces] の訳 [output] を、チャンクごとの確定（チャンクの原文 → 訳）と、訳し直すチャンクに分ける。 */
    fun finish(pieces: List<Piece>, output: String, target: Language): Outcome

    data class Outcome(val finals: List<Pair<String, String>>, val retry: List<String>)
}

/**
 * 1件ずつ訳すコア。チャンクを [MAX_LINES] 行・[MAX_CHARS] 字まで改行でつないで1回で訳し、訳文を改行で分けてチャンクへ戻す
 * （1件ずつ順に訳すので、1回ごとにかかる原文の前置き（PFML3の見出し）の処理を減らすため。2026-11-05、1チャンクずつだと1画面で20秒を超えた）。
 * 行数がずれたらつないだチャンクを全部、行数が合っても原文のまま写したチャンクはそのチャンクを、1チャンクずつ訳し直す。
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
            if (next.single || next.batch != null || chars + next.text.length > MAX_CHARS) break
            chunk += queue.removeFirst()
            chars += next.text.length
        }
        return chunk
    }

    override fun finish(pieces: List<Piece>, output: String, target: Language): TranslationCore.Outcome =
        finish(pieces, output) { source, translation -> TranslationChecks.untranslated(source, translation, target) }

    internal fun finish(
        pieces: List<Piece>,
        output: String,
        untranslated: (String, String) -> Boolean,
    ): TranslationCore.Outcome {
        if (pieces.size == 1) return TranslationCore.Outcome(listOf(pieces[0].text to output.trim()), emptyList())
        val lines = output.trim().split('\n').map { it.trim() }
        // 行がずれたら、つないだチャンクを1つずつ訳し直す
        if (lines.size != pieces.size) return TranslationCore.Outcome(emptyList(), pieces.map { it.text })
        val finals = mutableListOf<Pair<String, String>>()
        val retry = mutableListOf<String>()
        pieces.forEachIndexed { k, p ->
            // 行数が合っても、原文のまま写した行は訳し直す（言語の混ざったブロックで起きる）
            if (untranslated(p.text, lines[k])) retry += p.text else finals += p.text to lines[k]
        }
        return TranslationCore.Outcome(finals, retry)
    }

    companion object {
        const val MAX_LINES = 8
        const val MAX_CHARS = 400
    }
}

/**
 * 同時に訳すコア。チャンクをつながずに1チャンクを1回で訳す（2026-11-12、作者の判断「並列でできるなら、並列の場合は訳し直しのロジックはなしでいい」）。
 * つなぐと行のずれ・写しで訳し直しが毎回1〜4件出て、その行が遅れた。長い文は文の切れ目で細かく分け（[TranslationChecks.PARALLEL_SPLIT]）、
 * 分けたチャンクをほかのシーケンスでも訳す（Discordのボットの長文が最後に1本だけで訳され、1画面に7〜9秒かかった）。
 */
class ParallelCore(override val slots: Int) : TranslationCore {
    override val splitAt = TranslationChecks.PARALLEL_SPLIT

    override fun plan(queue: ArrayDeque<Piece>): List<Piece> = listOf(queue.removeFirst())

    override fun finish(pieces: List<Piece>, output: String, target: Language) =
        TranslationCore.Outcome(pieces.map { it.text to output.trim() }, emptyList())
}

/** 「同時に訳す数」の設定に合うコア。 */
fun coreFor(parallel: Int): TranslationCore = if (parallel <= 1) SequentialCore() else ParallelCore(parallel)

/**
 * ストリームに一度に渡しておくと、エンジンを無駄なく使える文の数（[coreFor] と同じ設定から決める）。1件ずつのコアは
 * 最大 [SequentialCore.MAX_LINES] 行をつないで1回で訳すので、その数まで。同時に訳すコアは、同時に訳す数まで。
 * 渡しておく数を絞って、その時点で選ぶ呼び出し（カメラ翻訳）が使う。コアの訳し方を呼ぶ側に漏らさないため、ここで決める。
 */
fun intakeFor(parallel: Int): Int = if (parallel <= 1) SequentialCore.MAX_LINES else parallel

/** 呼ぶ側が組んだバッチは境界を保ち、分割した長文や訳し直しは単独で依頼する。 */
internal object PlannedBatch {
    private val fallback = SequentialCore()

    fun validate(segments: List<Segment>) {
        require(segments.all { it.text.isNotBlank() && '\n' !in it.text && '\r' !in it.text })
        require(segments.size == 1 || (segments.size <= SequentialCore.MAX_LINES &&
            segments.sumOf { it.text.length } <= SequentialCore.MAX_CHARS))
    }

    fun take(queue: ArrayDeque<Piece>): List<Piece> {
        val first = queue.removeFirst()
        if (first.single) return listOf(first)
        return buildList {
            add(first)
            while (queue.firstOrNull()?.let { it.batch == first.batch && !it.single } == true) {
                add(queue.removeFirst())
            }
        }
    }

    fun finish(pieces: List<Piece>, output: String, target: Language, cut: Boolean): TranslationCore.Outcome {
        if (cut && pieces.size > 1) return TranslationCore.Outcome(emptyList(), pieces.map { it.text })
        return fallback.finish(pieces, output) { source, translation ->
            TranslationChecks.untranslatedMarked(source, translation, target)
        }
    }
}
