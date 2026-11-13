package app.plamotranslate.ocr

/**
 * 行を読んだ読み手（docs/設計/画面翻訳.md「読み手」）。読み手ごとの事情（読める文字種、採ってよい行、縦書きを読めるか）は
 * ここに置き、行を選ぶ側（[BlockMerger]・[LineChoice]）は読み手の名前でなくこの性質で扱う。
 *
 * 並びの順は、同じ行の読みを並べる順（位置の基準になる行の順）でもある。
 */
internal enum class Source(
    /** PP-OCR のモデルで読むか（重いので、白縁の読み比べの2回目には使わない）。 */
    val paddle: Boolean = false,
    /** 縦書きを読めるか。読める読み手の行は先に採り、重なるほかの行（縦書きを横に読み違えた行）を捨てる。 */
    val readsVertical: Boolean = false,
    /** 縦書きの行を先に採るとき、確信度がこれ未満なら、韓国語の読み手が読めた範囲を韓国語の行に譲る。 */
    val yieldsToKoreanBelow: Float? = null,
    /** 韓国語の読み手か（縦書きの行が譲る相手、行の文字種を決めるハングルの数）。 */
    val korean: Boolean = false,
) {
    /** PP-OCRv6 manga。中国語・日本語を読み、縦書きも読める。韓国語は読めない。 */
    PADDLE_ZH_JA(paddle = true, readsVertical = true, yieldsToKoreanBelow = 0.8f) {
        override fun accepts(line: Candidate) = ScriptDetector.hasHan(line.text) || ScriptDetector.hasKana(line.text)
    },
    MLKIT_ZH,
    MLKIT_JA,
    MLKIT_KO(korean = true),

    /** 英字専用。漢字・仮名・ハングルを読めないので、英字だけの行を採る。 */
    MLKIT_LATIN {
        override fun accepts(line: Candidate) = ScriptDetector.detect(line.text) == Script.LATIN
    },
    ;

    /** この読み手の行として採ってよいか。読み手が読めない文字種の行（読み違い）を落とす。 */
    open fun accepts(line: Candidate): Boolean = true
}
