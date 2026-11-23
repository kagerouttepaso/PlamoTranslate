package app.plamotranslate.models

import android.content.Context
import app.plamotranslate.ocr.PaddleModels
import app.plamotranslate.pipeline.models.SharedModelCatalog
import app.plamotranslate.pipeline.models.TranslationModel as SharedTranslationModel
import app.plamotranslate.pipeline.speech.SpeechModel
import java.io.File

typealias ModelFile = app.plamotranslate.pipeline.models.ModelFile

internal fun ModelFile.file(context: Context): File = File(ModelCatalog.modelsDir(context), path)

/** 手動配置した GGUF も、認識器に渡す前にダウンローダーと同じ SHA-256 で確かめる。 */
internal fun ModelFile.present(context: Context, verify: Boolean): Boolean {
    val file = file(context)
    if (!file.isFile || file.length() != size) return false
    if (this !in SharedModelCatalog.qwenGguf) return true
    val attributes = try {
        java.nio.file.Files.readAttributes(file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java)
    } catch (_: java.io.IOException) {
        return false
    }
    val stamp = "${attributes.fileKey()}/${attributes.lastModifiedTime()}/$sha256"
    val receipts = context.getSharedPreferences("verified_models", Context.MODE_PRIVATE)
    return when (receipts.getString(path, null)) {
        stamp -> true
        "invalid/$stamp" -> false
        else -> if (!verify) true else {
            val valid = ModelDownloads.sha256(file).equals(sha256, ignoreCase = true)
            receipts.edit().putString(path, if (valid) stamp else "invalid/$stamp").apply()
            valid
        }
    }
}

enum class TranslationModel(private val model: SharedTranslationModel) {
    SMALL(SharedTranslationModel.SMALL),
    LARGE(SharedTranslationModel.LARGE),
    ;

    val label get() = model.label
    val note get() = model.note
    val quality get() = model.quality
    val file get() = model.file
    val origin get() = model.origin

    fun isPresent(context: Context): Boolean = file.file(context).length() == file.size
}

/**
 * 一緒にダウンロードするモデルの組。初回の準備の画面と設定の画面で、使うモデルを選ぶと、その組を待ち行列に足す
 * （[ModelDownloads]。上から順に1組ずつ取る）。
 * 初回の準備がそろったとみなすのは、必須の組（[BASE]）と、翻訳・音声認識のモデルがそれぞれどれか1つ置かれたとき（[ready]）。
 */
enum class ModelBundle(
    /** 種類（必須・翻訳・音声認識・読み上げ）。準備の画面の欄の名前と、一覧の行の頭にそろえて使う。 */
    val group: String,
    /** その種類の中の名前。 */
    val title: String,
) {
    // 並びは、準備の画面の欄の並びと待ち行列の並びの正本（必須・翻訳・音声認識・読み上げ）。変えるときは両方が変わる
    /** どの選び方でも要る：話しているかの判定（Silero VAD）と、漫画・韓国語の文字の認識（PP-OCR）。 */
    BASE("必須", "話しているかの判定・文字の認識"),
    SMALL_TRANSLATION("翻訳", "0.8B"),
    LARGE_TRANSLATION("翻訳", "2B"),
    SENSE_VOICE("音声認識", "SenseVoice Small"),

    /** Qwen3-ASR と、その前に置く言語の識別（Whisper tiny）。 */
    QWEN3_ASR("音声認識", "Qwen3-ASR 0.6B（言語の識別 Whisper tiny）"),

    /** 読み上げのモデル（Supertonic 3）。 */
    SPEECH("読み上げ", "Supertonic 3"),
    ;

    /** 一覧の行に出す名前（「翻訳：0.8B」）。 */
    val label: String get() = "$group：$title"

    val files: List<ModelFile>
        get() = when (this) {
            BASE -> ModelCatalog.base
            SMALL_TRANSLATION -> listOf(TranslationModel.SMALL.file)
            LARGE_TRANSLATION -> listOf(TranslationModel.LARGE.file)
            SENSE_VOICE -> SharedModelCatalog.recognition(SpeechModel.SENSE_VOICE)
            QWEN3_ASR -> SharedModelCatalog.recognition(SpeechModel.QWEN3_ASR) + SharedModelCatalog.whisperTiny
            SPEECH -> ModelCatalog.speech
        }

    val size: Long get() = files.sumOf { it.size }

    /**
     * まだ置かれていないファイル。大きさだけで見る（毎回ハッシュを計算すると起動が遅くなる）。
     * ダウンロードしたものは置く前にハッシュを照合している。
     */
    fun missing(context: Context, verify: Boolean = false): List<ModelFile> = files.filterNot { it.present(context, verify) }

    fun isPresent(context: Context): Boolean = missing(context).isEmpty()

    companion object {
        fun of(model: TranslationModel): ModelBundle = when (model) {
            TranslationModel.SMALL -> SMALL_TRANSLATION
            TranslationModel.LARGE -> LARGE_TRANSLATION
        }

        fun of(model: SpeechModel): ModelBundle = when (model) {
            SpeechModel.SENSE_VOICE -> SENSE_VOICE
            SpeechModel.QWEN3_ASR -> QWEN3_ASR
        }

        /** 初回の準備がそろったか（トップ画面を出せるか）。 */
        fun ready(context: Context): Boolean =
            BASE.isPresent(context) &&
                TranslationModel.entries.any { of(it).isPresent(context) } &&
                SpeechModel.entries.any { of(it).isPresent(context) }
    }
}

object ModelCatalog {
    private const val HF = "https://huggingface.co"

    /** PP-OCR のモデルの置き場。中のファイルの並びの正本は core-ocr の [PaddleModels]。 */
    private const val OCR = "ocr"
    private const val MANGA = "$HF/Kellenok/PP-OCRv6_manga/resolve/de0e793da4e213f4b13c738204fe2a832f16d57c"

    /**
     * 韓国語の文字の認識（`PaddlePaddle/korean_PP-OCRv5_mobile_rec` を ONNX にしたもの、Apache-2.0）。
     * 公式は Paddle の形式だけなので、公式から変換して公開されている版（Wycliffe Associates の DocScanner。旧 mxaln/docscanner-ocr から移った）を使う。PCで公式の重み（コミット c02ecaf1）を
     * 変換したものと出力が一致することを確かめた（2026-11-13、docs/判断/2026-11-13_韓国語の飾り文字にPP-OCRの韓国語の認識を足す.md）。
     */
    private const val KOREAN_OCR = "$HF/wycliffeassociates/docscanner-ocr/resolve/354549cf968c1d8883482a1dc74dde9c762ebf53/korean"

    /** どの選び方でも要るファイル（[ModelBundle.BASE]）。 */
    val base = listOf(
        SharedModelCatalog.sileroVad,
        ModelFile(
            label = "漫画の文字の検出（PP-OCRv6 manga）",
            url = "$MANGA/det/manga_det_v0.2.onnx",
            path = "$OCR/${PaddleModels.DETECTOR}",
            size = 1_816_954,
            sha256 = "d132078c46e292b226fb5a2ca52a7612ad319262dfdf493e7d8e3be435295978",
        ),
        ModelFile(
            label = "漫画の文字の認識（PP-OCRv6 manga）",
            url = "$MANGA/rec/manga_rec_v0.2.onnx",
            path = "$OCR/${PaddleModels.MANGA_REC}",
            size = 21_167_540,
            sha256 = "de12c84c63e62c80339e882e675983d886670dcb6f0147e1ed041afd6fa81888",
        ),
        ModelFile(
            label = "漫画の文字の表",
            url = "$MANGA/ppocrv6_dict.txt",
            path = "$OCR/${PaddleModels.MANGA_DICT}",
            size = 74_947,
            sha256 = "b5f2bfe2bdd9448429e3e82b51c789775d9b42f2403d082b00662eb77e401c5d",
        ),
        ModelFile(
            label = "韓国語の文字の認識（PP-OCRv5 korean）",
            url = "$KOREAN_OCR/rec_model.onnx",
            path = "$OCR/${PaddleModels.KOREAN_REC}",
            size = 13_441_299,
            sha256 = "03525a1dcf74ceff88c9cbe89f100c2a7c7f9567afffb0ee2cdea8303ec5c238",
        ),
        ModelFile(
            label = "韓国語の文字の表",
            url = "$KOREAN_OCR/charset.json",
            path = "$OCR/${PaddleModels.KOREAN_DICT}",
            size = 83_288,
            sha256 = "e600744eabcf1db6513518f7e09105cdb746ee0504ef110b25478147d9f0aa81",
        ),
    )

    /**
     * 読み上げのモデル（Supertonic 3、約220MB）。設定の画面で「読み上げ」をSupertonic 3にしたときだけ取る
     * （2026-11-08、作者の要望）。sherpa-onnx向けのint8版を使い、vocoderだけ公式のfp32版にする。
     * int8のvocoderは話している最中にザラつきが乗り、ステップ数を増やしても消えなかったため（作者が聞いて指摘、`docs/判断/2026-11-08_読み上げのvocoderだけfp32版にしステップ数を既定8にする.md`）。
     * sherpa-onnxのint8版のonnxは公式版をそのまま量子化したもので、公式のvocoderと差し替えて動く。
     * モデルのライセンスはOpenRAIL-M（用途の制限つき）。
     */
    private const val SUPERTONIC = "$HF/csukuangfj2/sherpa-onnx-supertonic-3-tts-int8-2026-05-11/resolve/cca5a0e6c96e1d2c720986bf7e75fcc81dee3ae4"
    private const val SUPERTONIC_OFFICIAL = "$HF/Supertone/supertonic-3/resolve/3cadd1ee6394adea1bd021217a0e650ede09a323/onnx"
    const val SPEECH_DIR = "tts/supertonic-3"
    val speech = listOf(
        speechFile(SUPERTONIC, "duration_predictor.int8.onnx", 3_700_147L, "c3eb91414d5ff8a7a239b7fe9e34e7e2bf8a8140d8375ffb14718b1c639325db"),
        speechFile(SUPERTONIC, "text_encoder.int8.onnx", 36_416_150L, "c7befd5ea8c3119769e8a6c1486c4edc6a3bc8365c67621c881bbb774b9902ff"),
        speechFile(SUPERTONIC, "vector_estimator.int8.onnx", 78_400_833L, "20cd86fa5c6effedfda0e7cffe5b0569ca401c440a0c3a1d72bf39286c0db3fd"),
        speechFile(SUPERTONIC_OFFICIAL, "vocoder.onnx", 101_424_195L, "085de76dd8e8d5836d6ca66826601f615939218f90e519f70ee8a36ed2a4c4ba"),
        speechFile(SUPERTONIC, "tts.json", 8_253L, "42078d3aef1cd43ab43021f3c54f47d2d75ceb4e75f627f118890128b06a0d09"),
        speechFile(SUPERTONIC, "unicode_indexer.bin", 262_144L, "8402ca48e5189a8950138580b0fff64db6f072f24ac07cd54ba8b2fbb9883b30"),
        speechFile(SUPERTONIC, "voice.bin", 517_168L, "67d5209b0ee8ce6c74105ffbe12fe6a7628aea3b4ba2fcb308a4a67938a93ce8"),
    )

    private fun speechFile(base: String, name: String, size: Long, sha256: String) =
        ModelFile(label = "読み上げ（Supertonic 3）$name", url = "$base/$name", path = "$SPEECH_DIR/$name", size = size, sha256 = sha256)

    fun speechDir(context: Context): File = File(modelsDir(context), SPEECH_DIR)

    fun speechPresent(context: Context): Boolean = ModelBundle.SPEECH.missing(context).isEmpty()

    fun modelsDir(context: Context): File = File(context.getExternalFilesDir(null), "models")

    /** 音声認識（SenseVoice・Silero VAD）の置き場。会話翻訳とテキスト翻訳の音声入力が使う。 */
    fun asrDir(context: Context): File = File(modelsDir(context), "asr")

    /** PP-OCR のモデルの置き場（`manga/`・`korean/`）。無ければML Kitだけで動く。 */
    fun ocrDir(context: Context): File = File(modelsDir(context), OCR)

}
