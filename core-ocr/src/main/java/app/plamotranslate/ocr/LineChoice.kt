package app.plamotranslate.ocr

/** 同じ行を1つの読み手が読んだ版。位置は持たない（[LineChoice] は位置を見ない）。 */
internal data class Reading(val source: Source, val text: String, val confidence: Float? = null)

/**
 * 同じ行の読みの一覧から1つを選ぶ（docs/設計/画面翻訳.md「行の選び方とまとめ方」の手順3）。
 * 読み手が得意な文字種の字をいくつ読めたかで行の文字種を決め、[ChoicePolicy.priority] の順で採る。崩れた読み（[TextQuality]）は飛ばす。
 * 読み手の名前では分岐しない。
 */
internal object LineChoice {
    fun choose(readings: List<Reading>): Reading {
        // 縦書きを読める読み手の行は、手順1で先に採ってある
        readings.firstOrNull { it.source.readsVertical }?.let { return it }
        val ordered = readings.sortedBy { it.source.ordinal }
        val counts = Script.entries.associateWith { script -> count(ordered, script) }
        val kana = counts.getValue(Script.KANA)
        val hangul = counts.getValue(Script.HANGUL)
        val han = counts.getValue(Script.HAN)

        // 英語（ラテン文字だけの行）：英字専用の読み手を優先し、読めていなければ、崩れずラテン文字を最も多く読めたもの
        if (kana == 0 && hangul == 0 && han == 0) {
            pick(ordered, Script.LATIN) { ScriptDetector.hasLatin(it.text) }?.let { return it }
            ordered.filter { ok(it) && ScriptDetector.hasLatin(it.text) }
                .maxByOrNull { r -> r.text.count { it in 'A'..'Z' || it in 'a'..'z' } }
                ?.let { return it }
        }
        // 日本語の文に韓国語の引用が混ざる行は、仮名の数がハングル以上なら日本語の行とみなす。
        // 縦書きの中国語は韓国語の認識器がハングルに読み違える（「째요또터」）ので、
        // 中国語の認識器が読んだ漢字の数がハングル以上なら中国語の行とみなす（2026-11-05評価）。
        val order = buildList {
            if (kana >= 2 && kana >= hangul) add(Script.KANA)
            if (hangul > 0 && hangul > han) add(Script.HANGUL)
            if (han > 0) add(Script.HAN)
            if (kana > 0) add(Script.KANA)
            addAll(ChoicePolicy.fallback)
        }
        for (script in order) pick(ordered, script)?.let { return it }
        // どれも崩れている場合は、後段（訳す対象の判定）で落とす
        // 英字専用の読み手だけが読んだ行（崩れた英字など）もここに来る（2026-11-06、カメラで落ちた）
        return ChoicePolicy.fallback.firstNotNullOfOrNull { script -> inOrder(ordered, script).firstOrNull() } ?: ordered.first()
    }

    /** [script] の優先順で、崩れていない最初の読み。空白を除いて同じ文字なら、空白を多く保つ読みを採る。 */
    private fun pick(readings: List<Reading>, script: Script, also: (Reading) -> Boolean = { true }): Reading? {
        val candidates = inOrder(readings, script).filter { ok(it) && also(it) }
        val first = candidates.firstOrNull() ?: return null
        return candidates.filter { compact(it.text) == compact(first.text) }.maxByOrNull { spaces(it.text) }
            ?.takeIf { spaces(it.text) > spaces(first.text) } ?: first
    }

    private fun inOrder(readings: List<Reading>, script: Script): List<Reading> {
        val sources = ChoicePolicy.priority[script].orEmpty()
        return sources.mapNotNull { s -> readings.firstOrNull { it.source == s } }
    }

    /** 得意な文字種が [script] の読み手が、その文字種の字をいくつ読めたか（読み手が複数なら多い方）。 */
    private fun count(readings: List<Reading>, script: Script): Int =
        readings.filter { it.source.specialty == script }.maxOfOrNull { r -> r.text.count { matches(script, it) } } ?: 0

    private fun matches(script: Script, c: Char): Boolean = when (script) {
        Script.KANA -> ScriptDetector.hasKana(c.toString())
        Script.HANGUL -> ScriptDetector.hasHangul(c.toString())
        Script.HAN -> ScriptDetector.hasHan(c.toString())
        Script.LATIN -> c in 'A'..'Z' || c in 'a'..'z'
        Script.OTHER -> false
    }

    private fun ok(r: Reading) = !TextQuality.looksGarbled(r.text)
    private fun compact(text: String) = text.filterNot { it.isWhitespace() }
    private fun spaces(text: String) = text.count { it.isWhitespace() }
}
