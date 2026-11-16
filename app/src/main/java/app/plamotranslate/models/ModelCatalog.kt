package app.plamotranslate.models

import android.content.Context
import app.plamotranslate.ocr.PaddleModels
import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.translation.ModelOrigin
import java.io.File

/**
 * アプリが使うモデルのファイル。初回起動時に選んだ組をダウンロードして、外部ファイル置き場の `models/` に置く
 * （2026-11-06、APKを配布するため。それまではPCからadbで置いていた。docs/手順/開発環境.md）。
 *
 * [sha256] と [size] は、ダウンロード元から取って確かめた値。ダウンロード後に照合し、違えば使わない。
 *
 * Hugging FaceのURLは `resolve/main/` ではなく、コミットを指定する（2026-11-08）。配布元は公開後もファイルを
 * 差し替える。pfnetは11-06〜11-08にGGUFのチャットテンプレートを4回更新し、そのたびに大きさとハッシュが変わった。
 * 漫画用OCRは置き場所を変え、mainの旧パスは404になった。mainを指すと、照合が合わずにダウンロードが必ず失敗する。
 * 新しい版に上げるときは、内容を確かめてから、コミット・大きさ・ハッシュをまとめて書き換える（`docs/手順/開発環境.md`「モデルの版を上げる」）。
 */
data class ModelFile(
    /** 画面に出す名前。 */
    val label: String,
    val url: String,
    /** `models/` からの置き場所。 */
    val path: String,
    val size: Long,
    val sha256: String,
) {
    fun file(context: Context): File = File(ModelCatalog.modelsDir(context), path)
}

/**
 * 翻訳モデルの大きさ。既定は0.8Bで、2Bは作者が選んだときだけ追加でダウンロードする
 * （2026-11-06、作者の要望「2Bも選べるようにして、翻訳品質と速度の違いをチェックしたい」）。
 * 量子化はどちらもQ4_K_Mにそろえ、比べるときに大きさの違いだけが効くようにする。
 */
enum class TranslationModel(val label: String, val note: String, val quality: Int, val file: ModelFile) {
    SMALL(
        "0.8B",
        "速い。慣用句や韓国語の口語で意味を取り違えやすい",
        quality = 0,
        ModelFile(
            label = "翻訳モデル（PLaMo 3 Translate 0.8B）",
            url = "https://huggingface.co/pfnet/plamo-3-translate-0.8b-GGUF/resolve/e10c327b7a7ce84ffc427cb4b09360b781429fda/plamo-3-translate-0.8b-Q4_K_M.gguf",
            path = "llm/plamo-3-translate-0.8b-Q4_K_M.gguf",
            size = 573_126_848,
            sha256 = "70891ec70dc7ab4205745a38307867c2881b99239f20327927633abfe4bc9ec6",
        ),
    ),
    LARGE(
        "2B",
        "訳が正確。0.8Bの約2.5倍遅い（短い文で0.3〜1.4秒）",
        quality = 1,
        ModelFile(
            label = "翻訳モデル（PLaMo 3 Translate 2B）",
            url = "https://huggingface.co/pfnet/plamo-3-translate-2b-GGUF/resolve/d44589ecd5287cd64452e9cd1e2cfdad904fe216/plamo-3-translate-2b-Q4_K_M.gguf",
            path = "llm/plamo-3-translate-2b-Q4_K_M.gguf",
            size = 1_807_202_144,
            sha256 = "2189a8a0b4b359f59443ec36cfb9c9c3f603a678c00dbcee8598b3f7ea7c12fb",
        ),
    ),
    ;

    fun isPresent(context: Context): Boolean = file.file(context).length() == file.size

    /**
     * 翻訳のキャッシュで使う、訳したモデル（docs/設計/翻訳コア.md「キャッシュ」）。識別子は重みの SHA-256 の先頭、
     * 訳の品質（[quality]、大きいほど品質が高い）は端末のデータベースに残るので、並び順でなく値で持つ。キャッシュは品質の高いモデルの訳を優先する。
     * モデルを足すときは既存の値を変えずに値を割り当てる。既存の値を変えるなら、残った訳の品質が食い違うので
     * SqlitePersistentCache の DB_VERSION を上げてキャッシュを作り直す。
     */
    val origin: ModelOrigin get() = ModelOrigin(file.sha256.take(16), quality)
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
            SENSE_VOICE -> ModelCatalog.senseVoice
            QWEN3_ASR -> ModelCatalog.qwen3Asr
            SPEECH -> ModelCatalog.speech
        }

    val size: Long get() = files.sumOf { it.size }

    /**
     * まだ置かれていないファイル。大きさだけで見る（毎回ハッシュを計算すると起動が遅くなる）。
     * ダウンロードしたものは置く前にハッシュを照合している。
     */
    fun missing(context: Context): List<ModelFile> = files.filter { it.file(context).length() != it.size }

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
    private const val SENSE_VOICE = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"
    private const val MANGA = "$HF/Kellenok/PP-OCRv6_manga/resolve/de0e793da4e213f4b13c738204fe2a832f16d57c"

    /**
     * 韓国語の文字の認識（`PaddlePaddle/korean_PP-OCRv5_mobile_rec` を ONNX にしたもの、Apache-2.0）。
     * 公式は Paddle の形式だけなので、公式から変換して公開されている版（Wycliffe Associates の DocScanner。旧 mxaln/docscanner-ocr から移った）を使う。PCで公式の重み（コミット c02ecaf1）を
     * 変換したものと出力が一致することを確かめた（2026-11-13、docs/判断/2026-11-13_韓国語の飾り文字にPP-OCRの韓国語の認識を足す.md）。
     */
    private const val KOREAN_OCR = "$HF/wycliffeassociates/docscanner-ocr/resolve/354549cf968c1d8883482a1dc74dde9c762ebf53/korean"

    /** どの選び方でも要るファイル（[ModelBundle.BASE]）。 */
    val base = listOf(
        ModelFile(
            label = "話しているかの判定（Silero VAD）",
            url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
            path = "asr/silero_vad.onnx",
            size = 643_854,
            sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
        ),
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


    /** 音声認識 SenseVoice Small（[ModelBundle.SENSE_VOICE]）。 */
    val senseVoice = listOf(
        ModelFile(
            label = "音声認識（SenseVoice Small）",
            url = "$SENSE_VOICE/model.int8.onnx",
            path = "asr/sense-voice/model.int8.onnx",
            size = 239_233_841,
            sha256 = "c71f0ce00bec95b07744e116345e33d8cbbe08cef896382cf907bf4b51a2cd51",
        ),
        ModelFile(
            label = "音声認識の文字の表",
            url = "$SENSE_VOICE/tokens.txt",
            path = "asr/sense-voice/tokens.txt",
            size = 315_894,
            sha256 = "f449eb28dc567533d7fa59be34e2abca8784f771850c78a47fb731a31429a1dc",
        ),
    )

    /**
     * 音声認識 Qwen3-ASR 0.6B int8 と、言語の識別 Whisper tiny int8（[ModelBundle.QWEN3_ASR]）。sherpa-onnx の配布物
     * （GitHubのリリース `asr-models` の tar と同じファイル）を Hugging Face のコミットで固定して取る。
     * 置き場と並びの正本は core-asr の `Qwen3Recognizer`・`WhisperLanguageIdentifier`。
     */
    private const val QWEN3 = "$HF/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25/resolve/68818b2313fe77bd06f6a7c5068ff3ef59d02b8a"
    private const val WHISPER = "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"
    val qwen3Asr = listOf(
        asrFile("Qwen3-ASR conv_frontend", "$QWEN3/conv_frontend.onnx", "qwen3-asr/conv_frontend.onnx", 44_148_281, "d22dc4423e0940e49884e903d2ea2f7e5567c14fc1aed97e4e26d6b8f208ef9e"),
        asrFile("Qwen3-ASR encoder", "$QWEN3/encoder.int8.onnx", "qwen3-asr/encoder.int8.onnx", 182_491_662, "60748d3e6744a57c9c91e1b17424a6c2990567e8adceb0783940c03ed98fa9d9"),
        asrFile("Qwen3-ASR decoder", "$QWEN3/decoder.int8.onnx", "qwen3-asr/decoder.int8.onnx", 755_914_231, "4f6885be5959ae26af3089d38ee7972c5fafbeeb1cf8d5e76eab6d8b61ca5771"),
        asrFile("Qwen3-ASR 文字の表", "$QWEN3/tokenizer/vocab.json", "qwen3-asr/tokenizer/vocab.json", 2_776_833, "ca10d7e9fb3ed18575dd1e277a2579c16d108e32f27439684afa0e10b1440910"),
        asrFile("Qwen3-ASR 文字のつなぎ", "$QWEN3/tokenizer/merges.txt", "qwen3-asr/tokenizer/merges.txt", 1_671_853, "8831e4f1a044471340f7c0a83d7bd71306a5b867e95fd870f74d0c5308a904d5"),
        asrFile("Qwen3-ASR 文字の設定", "$QWEN3/tokenizer/tokenizer_config.json", "qwen3-asr/tokenizer/tokenizer_config.json", 12_487, "4942d005604266809309cabc9f4e9cb89ce855d59b14681fdc0e1cc62ea26c4c"),
        asrFile("言語の識別（Whisper tiny）encoder", "$WHISPER/tiny-encoder.int8.onnx", "whisper-tiny/tiny-encoder.int8.onnx", 12_937_772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
        asrFile("言語の識別（Whisper tiny）decoder", "$WHISPER/tiny-decoder.int8.onnx", "whisper-tiny/tiny-decoder.int8.onnx", 89_855_401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
    )

    private fun asrFile(label: String, url: String, path: String, size: Long, sha256: String) =
        ModelFile(label = "音声認識（$label）", url = url, path = "asr/$path", size = size, sha256 = sha256)

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
