package app.plamotranslate.ocr

/** 同じ行を1つの読み手が読んだ版。位置は持たない（[LineChoice] は位置を見ない）。 */
internal data class Reading(val source: Source, val text: String, val confidence: Float? = null)

/**
 * 同じ行の読みの一覧から1つを選ぶ（docs/設計/画面翻訳.md「行の選び方とまとめ方」の手順3）。
 * 読み手が自分の文字をいくつ読めたかで行の文字種を決め、文字種ごとの優先順で採る。崩れた読み（[TextQuality]）は飛ばす。
 *
 * Android に依存しないので、選び方は単体テストで確かめる。
 */
internal object LineChoice {
    fun choose(readings: List<Reading>): Reading {
        val by = readings.associateBy { it.source }
        // 縦書きを読める読み手の行は、手順1で先に採ってある
        readings.firstOrNull { it.source.readsVertical }?.let { return it }

        fun ok(r: Reading?) = r != null && !TextQuality.looksGarbled(r.text)
        fun firstOk(vararg sources: Source): Reading? = sources.firstNotNullOfOrNull { s -> by[s]?.takeIf { ok(it) } }

        val ja = by[Source.MLKIT_JA]
        val zh = by[Source.MLKIT_ZH]
        val koreans = readings.filter { it.source.korean }
        // 各読み手が自分の文字をいくつ読めたかで、その行の言語を決める。
        // 日本語の文に韓国語の引用が混ざる行は、仮名の数がハングル以上なら日本語の行とみなす。
        // 縦書きの中国語は韓国語の認識器がハングルに読み違える（「째요또터」）ので、
        // 中国語の認識器が読んだ漢字の数がハングル以上なら中国語の行とみなす（2026-11-05評価）。
        val kana = ja?.text?.count { ScriptDetector.hasKana(it.toString()) } ?: 0
        val hangul = koreans.maxOfOrNull { r -> r.text.count { ScriptDetector.hasHangul(it.toString()) } } ?: 0
        val han = zh?.text?.count { ScriptDetector.hasHan(it.toString()) } ?: 0

        // 英語（ラテン文字だけの行）：英字専用の読み手を優先し、読めていなければ、崩れずラテン文字を最も多く読めたもの
        if (kana == 0 && hangul == 0 && han == 0) {
            by[Source.MLKIT_LATIN]?.takeIf { ok(it) && ScriptDetector.hasLatin(it.text) }?.let { return it }
            readings.filter { ok(it) && ScriptDetector.hasLatin(it.text) }
                .maxByOrNull { r -> r.text.count { it in 'A'..'Z' || it in 'a'..'z' } }
                ?.let { return it }
        }
        if (kana >= 2 && kana >= hangul && ok(ja)) return ja!!
        if (hangul > 0 && hangul > han) korean(koreans.filter(::ok))?.let { return it }
        if (han > 0 && ok(zh)) return zh!!
        if (kana > 0 && ok(ja)) return ja!!
        firstOk(Source.MLKIT_ZH, Source.MLKIT_JA)?.let { return it }
        korean(koreans.filter(::ok))?.let { return it }
        // どれも崩れている場合は、後段（訳す対象の判定）で落とす
        // 英字専用の読み手だけが読んだ行（崩れた英字など）もここに来る（2026-11-06、カメラで落ちた）
        return zh ?: ja ?: koreans.firstOrNull() ?: readings.first()
    }

    /** 崩れていない韓国語の読みから、読み手の並びの順に1つ選ぶ。 */
    private fun korean(readings: List<Reading>): Reading? = readings.minByOrNull { it.source.ordinal }
}
