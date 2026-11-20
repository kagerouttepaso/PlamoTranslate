package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language

/**
 * 文字の種類。言語のコードと、認識した文の多数派の文字を、同じ基準で比べる。どの言語がどの文字で書かれるかの正本は
 * [ofLanguage] の1か所で、文字認識の認識器の組（画面翻訳・カメラ翻訳の原文の言語）もここから導く。
 */
enum class Script {
    JAPANESE, HAN, HANGUL, LATIN, CYRILLIC, ARABIC, DEVANAGARI, THAI, GREEK, HEBREW, OTHER;

    companion object {
        /** 言語が使う文字。 */
        fun of(language: Language): Script = ofLanguage(language.code)

        /**
         * 言語のコードが使う文字。日本語は仮名と漢字、中国語は漢字。挙げていない言語は [OTHER]（ラテン文字とみなさない。
         * 言語を足すときは、ここに文字を足す）。
         */
        fun ofLanguage(code: String): Script = when (code) {
            "ja" -> JAPANESE
            "zh", "yue", "zh-hans", "zh-hant" -> HAN
            "ko" -> HANGUL
            "ru", "bg", "be", "kk", "mk", "sr", "uk" -> CYRILLIC
            "ar", "fa", "ur" -> ARABIC
            "hi", "mr", "ne" -> DEVANAGARI
            "th" -> THAI
            "el" -> GREEK
            "he" -> HEBREW
            "hy", "ka", "km", "gu", "ta", "te", "bn", "pa", "ml", "my", "si" -> OTHER
            in LATIN_CODES -> LATIN
            else -> OTHER
        }

        /**
         * ラテン文字で書く言語のコード。アプリの言語（`Language`）に加え、音声認識が返しうるコード（Qwen3-ASR の言語、
         * Whisper の識別のラテン文字の言語。Whisper はジャワ語を `jw` と書く）も入れる。セルビア語はキリル文字で書くことが多いので
         * 入れない（上のキリル文字）。
         */
        private val LATIN_CODES = setOf(
            "en", "az", "it", "id", "uz", "et", "nl", "ca", "hr", "jv", "jw", "es", "sk", "sl", "sw", "ceb", "tl",
            "de", "hu", "eu", "fr", "vi", "pl", "ms", "lv", "lt", "pt", "tr", "sv", "da", "fi", "cs", "ro",
            "no", "nn", "is", "fo", "cy", "ga", "gl", "af", "sq", "bs", "mt", "lb", "oc", "br", "la", "mi",
            "haw", "ht", "ln", "mg", "sn", "so", "su", "yo", "ha", "tk",
        )

        /**
         * 日本語でも使う漢字の数。日本語の文字コード（Shift_JIS、JIS X 0208）に無い漢字（簡体字など）を、日本語では使わない
         * 漢字とみなす。漢字だけの文が日本語か中国語かを見分けるのに使う（[ScriptCounts.fit]）。JIS X 0208 に無い人名の漢字
         * （「髙」「﨑」など）も日本語では使わない字に数えるが、合うかは半分以上で見るので、ふつうの文には効かない。
         */
        fun japaneseHan(text: String): Int {
            val japanese = SHIFT_JIS.newEncoder()
            return text.count { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN && japanese.canEncode(it) }
        }

        private val SHIFT_JIS = java.nio.charset.Charset.forName("Shift_JIS")
    }
}

/**
 * 部品：文の文字を、文字の種類ごとに数える。言語との合い方（[fit]・[fits]）はここで決め、話し手の根拠を確かめる判定
 * （[LanguageEvidence]）と、文字の種類で言語を当てる判定（[ScriptLanguage]・会話の訳す向き）の両方がこれを使う。
 * 仮名（ひらがな・カタカナと長音符「ー」）は [Script.JAPANESE]、漢字は [Script.HAN] として数え、記号・数字・空白は数えない。
 * 文字はコードポイントで数える（絵文字や拡張漢字を2文字に数えない）。
 */
class ScriptCounts private constructor(
    private val counts: Map<Script, Int>,
    /** 漢字のうち、日本語でも使う字の数（[Script.japaneseHan]）。 */
    private val japaneseHan: Int,
    /** ラテン文字の語の数（ラテン文字の続き）。 */
    val latinWords: Int,
) {
    /** 数えた文字の数。 */
    val letters: Int = counts.values.sum()

    /** 数える文字が無い（記号・数字だけの文）。 */
    val isEmpty: Boolean get() = letters == 0

    /** 仮名を含むか。 */
    private val hasKana: Boolean get() = count(Script.JAPANESE) > 0

    fun count(script: Script): Int = counts[script] ?: 0

    /**
     * 言語 [code] の文字が、数えた文字に占める割合（0〜1）。どの言語かの確率ではなく、その言語の文字で書かれている度合いで、
     * 同じ文字を使う言語どうし（英語とフランス語など）は同じ値になる。日本語は仮名と、日本語でも使う漢字を数える
     * （簡体字など日本語で使わない漢字は数えないので、漢字だけの中国語の文は中国語の方が高い）。中国語は漢字を数え、
     * 仮名があれば0（日本語の文）。
     */
    fun fit(code: String): Float = if (letters == 0) 0f else matching(code).toFloat() / letters

    /** 言語 [code] の文字で書かれているか（その言語の文字が、数えた文字の半分以上）。 */
    fun fits(code: String): Boolean = letters > 0 && matching(code) * 2 >= letters

    private fun matching(code: String): Int = when (val script = Script.ofLanguage(code)) {
        Script.JAPANESE -> count(Script.JAPANESE) + japaneseHan
        Script.HAN -> if (hasKana) 0 else count(Script.HAN)
        else -> count(script)
    }

    companion object {
        fun of(text: String): ScriptCounts {
            val counts = HashMap<Script, Int>()
            var i = 0
            while (i < text.length) {
                val cp = text.codePointAt(i)
                i += Character.charCount(cp)
                val script = if (isKana(cp)) Script.JAPANESE else when (Character.UnicodeScript.of(cp)) {
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> Script.JAPANESE
                    Character.UnicodeScript.HAN -> Script.HAN
                    Character.UnicodeScript.HANGUL -> Script.HANGUL
                    Character.UnicodeScript.LATIN -> Script.LATIN
                    Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
                    Character.UnicodeScript.ARABIC -> Script.ARABIC
                    Character.UnicodeScript.DEVANAGARI -> Script.DEVANAGARI
                    Character.UnicodeScript.THAI -> Script.THAI
                    Character.UnicodeScript.GREEK -> Script.GREEK
                    Character.UnicodeScript.HEBREW -> Script.HEBREW
                    // 記号・数字・空白、割り当ての無い文字は数えない
                    Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED, Character.UnicodeScript.UNKNOWN -> null
                    else -> Script.OTHER
                } ?: continue
                counts.merge(script, 1, Int::plus)
            }
            val words = text.split(NON_LATIN).count { it.isNotEmpty() }
            val japaneseHan = if ((counts[Script.HAN] ?: 0) > 0) Script.japaneseHan(text) else 0
            return ScriptCounts(counts, japaneseHan, words)
        }

        /** 仮名（ひらがな・カタカナと長音符）。中黒「・」と「゠」は区切りの記号なので数えない。 */
        private fun isKana(cp: Int): Boolean = cp in 0x3041..0x30FF && cp != 0x30FB && cp != 0x30A0

        private val NON_LATIN = Regex("[^\\p{IsLatin}]+")
    }
}

/**
 * 判定：言語の判定が無い文の言語を、書き起こした文の文字の種類で当てる。候補（会話の言語）のうち文字の種類が合う
 * （[ScriptCounts.fits]）ものが1つならその言語。いくつも合えば合い方（[ScriptCounts.fit]）の一番高いもの、同じ高さなら
 * 決めずに null。漢字だけの文は日本語と中国語の両方に合い（日本語は漢字だけの文もあるため）、日本語で使わない漢字が
 * あれば中国語の方が高くなる。
 */
object ScriptLanguage {
    /** 言語の識別が使えないとき、書き起こした文の言語を会話の言語の組（[groups]）から当てる。 */
    fun guess(text: String, groups: Set<String>?): String? = groups?.let { pick(ScriptCounts.of(text), it) { code -> code } }

    /** [candidates] から [counts] の文字の種類が合うものを選ぶ（決め方は [ScriptLanguage]）。[code] は候補の言語のコード。 */
    fun <T> pick(counts: ScriptCounts, candidates: Collection<T>, code: (T) -> String): T? {
        val fitting = candidates.filter { counts.fits(code(it)) }.associateWith { counts.fit(code(it)) }
        val best = fitting.values.maxOrNull() ?: return null
        return fitting.filterValues { it == best }.keys.singleOrNull()
    }
}

/**
 * 判定：文ごとの書き起こしをつなぐ（書き起こしの重いモデルで、[SpeechSegmenter] が文ごとに書き起こしてつなぐときに使う）。
 * 語の間を空白で区切る文字（ラテン文字・キリル文字など）どうしは空白を挟み、空白で区切らない文字（仮名・漢字・タイ文字・
 * 全角の記号）が片側にあれば詰める。言語は、文字の多い方の文の言語にする。
 */
object TranscriptJoin {
    fun stitch(parts: List<Recognition>): Recognition {
        val spoken = parts.filter { it.text.isNotEmpty() }
        val text = spoken.fold("") { acc, r -> join(acc, r.text) }
        val lang = spoken.filter { it.lang != null }.groupBy { it.lang }
            .maxByOrNull { (_, rs) -> rs.sumOf { it.text.length } }?.key
        return Recognition(text, lang)
    }

    fun join(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        a.last().isWhitespace() || b.first().isWhitespace() -> a + b
        unspaced(a.last()) || unspaced(b.first()) -> a + b
        else -> "$a $b"
    }

    private fun unspaced(c: Char): Boolean = c in '　'..'〿' || c in '＀'..'￯' ||
        when (Character.UnicodeScript.of(c.code)) {
            Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA,
            Character.UnicodeScript.HAN, Character.UnicodeScript.THAI -> true
            else -> false
        }
}
