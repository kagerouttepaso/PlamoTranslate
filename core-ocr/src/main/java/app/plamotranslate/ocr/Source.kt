package app.plamotranslate.ocr

/**
 * 行を読んだ読み手の名前と、読み手の能力（docs/設計/画面翻訳.md「読み手」）。
 * 行をどう採るかの方針（確信度の下限・優先順）はここに置かず、[ChoicePolicy] が持つ。
 *
 * 並びの順は、同じ行の読みを並べる順（位置の基準になる行の順）でもある。
 */
internal enum class Source(
    /** 得意な文字種。行の文字種を決めるとき、この読み手が読めたその文字種の字を数える。 */
    val specialty: Script,
    /** 縦書きを読めるか。読める読み手の行は先に採り、重なるほかの行（縦書きを横に読み違えた行）を捨てる。 */
    val readsVertical: Boolean = false,
    /** 1文字ずつの位置を返し、縦書きを読める読み手が無いときに縦書きの列を組み直す元になるか（[VerticalColumns]）。 */
    val buildsColumns: Boolean = false,
) {
    /** PP-OCRv6 manga。中国語・日本語を読み、縦書きも読める。韓国語は読めない。 */
    PADDLE_ZH_JA(Script.HAN, readsVertical = true),
    MLKIT_ZH(Script.HAN, buildsColumns = true),
    MLKIT_JA(Script.KANA),
    MLKIT_KO(Script.HANGUL),

    /** PP-OCRv5 の韓国語の認識（`korean_PP-OCRv5_mobile_rec`。検出は PP-OCR 漫画用と同じ）。 */
    PADDLE_KO(Script.HANGUL),

    /** 英字専用。漢字・仮名・ハングルを読めない。 */
    MLKIT_LATIN(Script.LATIN),
}
