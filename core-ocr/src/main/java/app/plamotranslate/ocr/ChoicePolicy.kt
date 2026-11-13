package app.plamotranslate.ocr

/**
 * 行をどう採るかの方針（docs/設計/画面翻訳.md「読み手」「行の選び方とまとめ方」）。読み手を足すときは、ここの表に1行ずつ足す。
 * 文字の位置を見ないので、Android に依存せず単体テストで確かめる。
 */
internal object ChoicePolicy {
    /**
     * PP-OCR 韓国語の行を採る確信度の下限。画素画の飾りなどを読み違えた行は確信度が低い
     * （2026-11-13、トリッカルの画面1枚で、外れが0.16、読めた行が0.89〜1.00）。
     */
    const val PADDLE_KO_MIN_CONFIDENCE = 0.8f

    /**
     * 縦書きの行を先に採るとき、PP-OCR 漫画用の確信度がこれ未満なら、韓国語の読み手が読めた範囲を韓国語の行に譲る
     * （2026-11-05、PCで中国語の行は中央値0.99、韓国語の行は90%点でも0.75）。
     */
    const val VERTICAL_YIELD_BELOW = 0.8f

    /** 譲る相手になる行の、ハングルの最少の字数。 */
    const val YIELD_MIN_HANGUL = 2

    /**
     * 行の文字種ごとに、採る読み手の順。同じ順の中では、崩れていない最初の読みを採る。
     * ただし後ろの読みが、空白を除いて同じ文字で空白を多く保つなら、そちらを採る（PP-OCR は字の間の空白を落とすことがある）。
     */
    val priority: Map<Script, List<Source>> = mapOf(
        Script.LATIN to listOf(Source.MLKIT_LATIN),
        Script.KANA to listOf(Source.MLKIT_JA),
        Script.HANGUL to listOf(Source.PADDLE_KO, Source.MLKIT_KO),
        Script.HAN to listOf(Source.MLKIT_ZH),
    )

    /** 文字種が決まらない行で、崩れていない読みを探す順。 */
    val fallback: List<Script> = listOf(Script.HAN, Script.KANA, Script.HANGUL)

    /** 読み手が読めない文字種の行（読み違い）や、確信度の低い行を落とす。採ってよい行なら true。 */
    fun accepts(source: Source, text: String, confidence: Float?): Boolean = when (source) {
        Source.PADDLE_ZH_JA -> ScriptDetector.hasHan(text) || ScriptDetector.hasKana(text)
        Source.PADDLE_KO -> ScriptDetector.hasHangul(text) && (confidence ?: 0f) >= PADDLE_KO_MIN_CONFIDENCE
        Source.MLKIT_LATIN -> ScriptDetector.detect(text) == Script.LATIN
        Source.MLKIT_ZH, Source.MLKIT_JA, Source.MLKIT_KO -> true
    }

    /** 縦書きの行を、重なる韓国語の行に譲るか。 */
    fun yieldsToKorean(vertical: Reading): Boolean = (vertical.confidence ?: 0f) < VERTICAL_YIELD_BELOW

    /** 縦書きの行が譲る相手になる韓国語の行か。 */
    fun isKoreanLine(reading: Reading): Boolean =
        reading.source.specialty == Script.HANGUL &&
            reading.text.count { ScriptDetector.hasHangul(it.toString()) } >= YIELD_MIN_HANGUL &&
            !TextQuality.looksGarbled(reading.text)
}
