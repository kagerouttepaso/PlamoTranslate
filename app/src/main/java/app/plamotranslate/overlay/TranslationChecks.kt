package app.plamotranslate.overlay

import app.plamotranslate.llm.Language
import java.lang.Character.UnicodeScript

/**
 * まとめ訳しの点検（2026-11-12、作者の採用。Chrome拡張「PLaMo翻訳」の shared/text.js から移した）。
 * 拡張はこのアプリの BatchTranslator を手本にした後、Webページで見つかった失敗への対策を足していたので、それを持ち込む。
 */
internal object TranslationChecks {

    /** 長い1段落は、文の切れ目でおよそこの字数ずつに分けて訳す（拡張と同じ）。 */
    const val LONG_TEXT = 1200

    /**
     * 同時に訳すときは、文の切れ目でおよそこの字数ずつに分け、分けた片をほかの流れでも訳す（2026-11-12）。
     * 長いブロックが最後に1本の流れだけで訳され、ほかの流れが空いたままになるのを防ぐ（Discordのボットの長文で、
     * 1画面に7〜9秒かかった）。文より細かくは分けないので、1文がこれより長ければその文は1片のまま。
     */
    const val PARALLEL_SPLIT = 150

    /**
     * まとめ訳しの1行が訳されていないか。原文のままか、訳す先が日本語なのに、文字を含む原文に対して日本語の文字が1つもない。
     * 言語の混ざったまとまりでは、0.8Bが最初の言語の行だけ訳し、残りの行を原文のまま写すことがある
     * （拡張で2026-11-09、韓国語・フランス語・ドイツ語の3行）。その行だけ1行ずつ訳し直すために見る。
     * 短い英字の行（3語以下かつ24字以下。製品名・ボタン名）は、原文のまま返るのが正しいことが多いので訳し直さない
     * （拡張で、問い合わせが70回から53回に減った）。
     */
    fun untranslated(source: String, output: String, target: Language): Boolean {
        val plain = normalize(source)
        val latinOnly = plain.codePoints().allMatch { cp ->
            when (UnicodeScript.of(cp)) {
                UnicodeScript.LATIN, UnicodeScript.COMMON, UnicodeScript.INHERITED -> true
                else -> false
            }
        }
        if (latinOnly && plain.split(' ').size <= 3 && plain.length <= 24) return false
        val out = normalize(output)
        if (plain == out) return true
        if (target != Language.JA) return false
        return plain.any { it.isLetter() } && out.codePoints().noneMatch(::isJapanese)
    }

    /** 訳が原文をそのまま写したものか（空白の違いは見ない）。 */
    fun copied(source: String, output: String): Boolean = normalize(source) == normalize(output)

    /**
     * 生成が止まらなくなったか。止めて残す長さを返す（0 なら続ける）。
     * - 送った行数より2行以上多くなったら、送った行数までで止める。
     * - 末尾が、長さ p の同じ並びの3回の繰り返しなら止める（短い並びは、3回で24字以上のときだけ見る）。
     *   ただし訳文が原文の字数の1.2倍＋16字を超えるまでは止めない。原文にもともと繰り返しがある
     *   （表の単位やボタンが並ぶ）と、正しい訳文も繰り返しになるため（拡張で2026-11-09、「min」の並びを打ち切っていた）。
     */
    fun runaway(text: String, lines: Int, sourceChars: Int): Int {
        val split = text.split('\n')
        if (lines > 0 && split.size > lines + 1) return split.take(lines).joinToString("\n").length
        if (text.length <= sourceChars * 1.2 + 16) return 0
        var p = 2
        while (p <= 150 && p * 3 <= text.length) {
            if (p * 3 >= 24) {
                val a = text.substring(text.length - p)
                if (text.substring(text.length - 2 * p, text.length - p) == a &&
                    text.substring(text.length - 3 * p, text.length - 2 * p) == a
                ) return text.length - 2 * p
            }
            p++
        }
        return 0
    }

    /** 長い1段落を、文の切れ目でおよそ [max] 字ずつに分ける。短ければそのまま1つ。 */
    fun splitLong(text: String, max: Int = LONG_TEXT): List<String> {
        if (text.length <= max) return listOf(text)
        val sentences = SENTENCE.findAll(text).map { it.value }.toList().ifEmpty { listOf(text) }
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        for (s in sentences) {
            if (current.isNotEmpty() && current.length + s.length > max) {
                parts += current.toString()
                current.clear()
            }
            current.append(s)
        }
        if (current.isNotEmpty()) parts += current.toString()
        return parts
    }

    /** 分けて訳した片の訳をつなぐ。欧文の語の間なら空白、日本語・中国語などは詰める。 */
    fun joinPieces(pieces: List<String>): String = pieces.filter { it.isNotEmpty() }.fold(StringBuilder()) { sb, piece ->
        if (sb.isNotEmpty() && sb.last().isAsciiWordChar() && piece.first().isAsciiWordChar()) sb.append(' ')
        sb.append(piece)
    }.toString()

    private fun Char.isAsciiWordChar() = this < '\u0080' && (isLetterOrDigit() || this in ".,!?;:")

    private val SENTENCE = Regex("[^.!?。！？\\n]+[.!?。！？]*\\s*|\\n")
    private val SPACES = Regex("\\s+")

    private fun normalize(s: String) = s.replace(SPACES, " ").trim()

    private fun isJapanese(cp: Int) = when (UnicodeScript.of(cp)) {
        UnicodeScript.HIRAGANA, UnicodeScript.KATAKANA, UnicodeScript.HAN -> true
        else -> false
    }
}
