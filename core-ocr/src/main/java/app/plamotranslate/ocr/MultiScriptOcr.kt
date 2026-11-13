package app.plamotranslate.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.io.File

/**
 * 原文の言語で決めた読み手を並列に動かし、行ごとに文字種に合う読みを選んでまとめる（docs/設計/画面翻訳.md「読む流れ」）。
 * このクラスは流れの順番だけを持つ。読み手は [LineReader]、行の選び方とまとめ方は [BlockMerger]、言語ごとの読み手の組は [Recognizers]。
 *
 * - ML Kitの中国語・日本語・韓国語・英字の認識器（横書き）。
 * - [paddleDir]（`files/models/ocr/`）に PP-OCR のモデルがあれば、その読み手も動かす（[PaddleReader]）。
 *
 * [groupLines] が false なら、行を段落・吹き出しにまとめずに1行ずつ返す（カメラ翻訳。2026-11-06）。
 * カメラはコマごとに読み揺れるので、大きなまとまりにすると同じ文として揃わず、訳が出ないため。
 */
class MultiScriptOcr(paddleDir: File? = null, private val groupLines: Boolean = true) : OcrEngine {
    private val paddle: PaddleReader? = paddleDir?.let { runCatching { PaddleReader.load(it) }.getOrNull() }

    private val readers: List<LineReader> = listOfNotNull(
        paddle,
        MlKitReader(Source.MLKIT_ZH, TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build()), keepGlyphs = true),
        MlKitReader(Source.MLKIT_JA, TextRecognition.getClient(JapaneseTextRecognizerOptions.Builder().build())),
        MlKitReader(Source.MLKIT_KO, TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())),
        MlKitReader(Source.MLKIT_LATIN, TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)),
    )

    /** PP-OCR で読める読み手の一覧（評価・ログ用）。 */
    val paddleSources: Set<String> get() = paddle?.sources.orEmpty().map { it.name }.toSet()

    override suspend fun recognize(bitmap: Bitmap, language: OcrLanguage): List<OcrBlock> = read(bitmap, Recognizers.of(language))

    /**
     * 白縁取り・白抜きの文字にも強く読む（画面翻訳の動く画面のモード。[OutlinedText]）。元の画像と、RGBの最小値を取った画像を
     * 並べて読み、行ごとに崩れの少ない方を採る。文字認識が1回分多くかかる。最小値の画像には PP-OCR の読み手を使わない（遅い）。
     */
    suspend fun recognizeOutlined(bitmap: Bitmap, language: OcrLanguage): List<OcrBlock> = coroutineScope {
        val use = Recognizers.of(language)
        // 束ねた後のまとまりで比べると、言語の違う行が一緒に束ねられて比べられなかった（2026-11-12）ので、
        // 1行ずつ読んで行ごとに選び、選んだ行を束ねる
        val primary = async { read(bitmap, use, group = false) }
        val variant = async(Dispatchers.Default) {
            val white = OutlinedText.whiteness(bitmap)
            try {
                read(white, use.filterNot { it.paddle }.toSet(), group = false)
            } finally {
                white.recycle()
            }
        }
        BlockMerger.group(OutlinedText.pick(primary.await(), variant.await()), groupLines)
    }

    private suspend fun read(bitmap: Bitmap, use: Set<Source>, group: Boolean = groupLines): List<OcrBlock> = coroutineScope {
        val readings = readers.filter { r -> r.sources.any { it in use } }
            .map { r -> async { r.read(bitmap, use) } }
            .awaitAll()
        BlockMerger.merge(
            lines = readings.fold(mapOf()) { acc, r -> acc + r.lines },
            glyphs = readings.flatMap { it.glyphs },
            groupLines = group,
        )
    }

    override fun close() = readers.forEach { it.close() }
}

/**
 * 原文の言語ごとに動かす読み手の組（docs/設計/画面翻訳.md「原文の言語の指定」）。
 * 言語を決めると要らない読み手を止めるので速い（韓国語の漫画1ページ4.0秒→2.0秒、2026-11-05）。
 *
 * - 中国語：PP-OCR 漫画用（縦書き）＋ML Kit 中国語（横書き・ラテン文字）。
 * - 韓国語：PP-OCR 韓国語＋ML Kit 韓国語（PP-OCR 漫画用は韓国語を読めない）。PP-OCR 韓国語は飾り文字に強く、ML Kit は字の間の空白を保つ。
 *   PP-OCR 韓国語は自動には入れない（中国語・日本語の行の選び方に効くので、評価の画面で前後を比べてから）。
 * - ラテン文字（英語・フランス語・ベトナム語など）：ML Kit 英字だけ（英語の漫画の手書き風の大文字を、中国語の認識器は読み違えた。2026-11-06評価）。
 * - 自動：PP-OCR 漫画用と ML Kit の4つ。日本語の認識器は、日本語の行を見分けて訳さないために使う。
 */
internal object Recognizers {
    fun of(language: OcrLanguage): Set<Source> = when (language) {
        OcrLanguage.AUTO -> setOf(Source.PADDLE_ZH_JA, Source.MLKIT_ZH, Source.MLKIT_JA, Source.MLKIT_KO, Source.MLKIT_LATIN)
        OcrLanguage.CHINESE -> setOf(Source.PADDLE_ZH_JA, Source.MLKIT_ZH)
        OcrLanguage.KOREAN -> setOf(Source.MLKIT_KO, Source.PADDLE_KO)
        OcrLanguage.LATIN -> setOf(Source.MLKIT_LATIN)
    }
}
