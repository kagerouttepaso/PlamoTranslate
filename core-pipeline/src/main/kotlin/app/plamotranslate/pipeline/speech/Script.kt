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

        /** 文の多数派の文字。仮名が1つでもあれば日本語（漢字だけの日本語もあるが、仮名の無い文は中国語と区別できない）。 */
        fun ofText(text: String): Script? {
            val counts = HashMap<Script, Int>()
            var kana = 0
            for (c in text) {
                val script = when (Character.UnicodeScript.of(c.code)) {
                    Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA -> { kana++; JAPANESE }
                    Character.UnicodeScript.HAN -> HAN
                    Character.UnicodeScript.HANGUL -> HANGUL
                    Character.UnicodeScript.LATIN -> LATIN
                    Character.UnicodeScript.CYRILLIC -> CYRILLIC
                    Character.UnicodeScript.ARABIC -> ARABIC
                    Character.UnicodeScript.DEVANAGARI -> DEVANAGARI
                    Character.UnicodeScript.THAI -> THAI
                    Character.UnicodeScript.GREEK -> GREEK
                    Character.UnicodeScript.HEBREW -> HEBREW
                    Character.UnicodeScript.COMMON, Character.UnicodeScript.INHERITED -> null
                    else -> OTHER
                } ?: continue
                counts.merge(script, 1, Int::plus)
            }
            if (kana > 0) {
                // 仮名と漢字はまとめて日本語の文字として数える
                counts[JAPANESE] = kana + (counts.remove(HAN) ?: 0)
            }
            return counts.maxByOrNull { it.value }?.key
        }

        /**
         * 日本語では使わない漢字（簡体字など）を含むか。日本語の文字コード（Shift_JIS、JIS X 0208）に無い漢字を、
         * 中国語にしか無い漢字とみなす。漢字だけの文が日本語か中国語かを見分けるのに使う。
         */
        fun hasChineseOnlyHan(text: String): Boolean {
            val japanese = SHIFT_JIS.newEncoder()
            return text.any { Character.UnicodeScript.of(it.code) == Character.UnicodeScript.HAN && !japanese.canEncode(it) }
        }

        private val SHIFT_JIS = java.nio.charset.Charset.forName("Shift_JIS")
    }
}

/**
 * 判定：言語の識別が使えないとき、書き起こした文の文字の種類で言語を決める。
 * 会話の言語（[groups]）のうち、文字の種類が合うものが1つだけならその言語、決まらなければ null。
 * 漢字だけの文は、日本語と中国語の両方に合う（日本語は漢字だけの文もあるため）。ただし日本語では使わない漢字
 * （[Script.hasChineseOnlyHan]）があれば中国語にだけ合う。
 */
object ScriptLanguage {
    fun guess(text: String, groups: Set<String>?): String? {
        if (groups == null) return null
        val script = Script.ofText(text) ?: return null
        val matches = groups.filter { lang ->
            val s = Script.ofLanguage(lang)
            s == script || (script == Script.HAN && s == Script.JAPANESE && !Script.hasChineseOnlyHan(text))
        }
        return matches.singleOrNull()
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
