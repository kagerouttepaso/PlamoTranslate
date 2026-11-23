package app.plamotranslate.pipeline.models

import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.pipeline.translation.ModelOrigin

/**
 * 配布元からダウンロードするモデルのファイル。各アプリがモデル用のフォルダーに置く。
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
    /** `models/` からの相対パス。Windows の翻訳モデルはファイル名だけを使う。 */
    val path: String,
    val size: Long,
    val sha256: String,
)

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

    /**
     * 翻訳のキャッシュで使う、訳したモデル（docs/設計/翻訳コア.md「キャッシュ」）。識別子は重みの SHA-256 の先頭、
     * 訳の品質（[quality]、大きいほど品質が高い）は端末のデータベースに残るので、並び順でなく値で持つ。キャッシュは品質の高いモデルの訳を優先する。
     * モデルを足すときは既存の値を変えずに値を割り当てる。既存の値を変えるなら、残った訳の品質が食い違うので
     * SqlitePersistentCache の DB_VERSION を上げてキャッシュを作り直す。
     */
    val origin: ModelOrigin get() = ModelOrigin(file.sha256.take(16), quality)
}

/** Android と Windows が使う音声認識モデルの配布情報。 */
object SharedModelCatalog {
    private const val HF = "https://huggingface.co"
    private const val SENSE_VOICE = "$HF/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17/resolve/2365baeacb507f821a0c8120fcee3d484dba7a07"

    val sileroVad = ModelFile(
        label = "話しているかの判定（Silero VAD）",
        url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx",
        path = "asr/silero_vad.onnx",
        size = 643_854,
        sha256 = "9e2449e1087496d8d4caba907f23e0bd3f78d91fa552479bb9c23ac09cbb1fd6",
    )

    /** 音声認識 SenseVoice Small。 */
    private val senseVoice = listOf(
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

    private const val WHISPER = "$HF/csukuangfj/sherpa-onnx-whisper-tiny/resolve/65176e2deb88badc814a94058666cadccc29b61c"
    val whisperTiny = listOf(
        asrFile("言語の識別（Whisper tiny）encoder", "$WHISPER/tiny-encoder.int8.onnx", "whisper-tiny/tiny-encoder.int8.onnx", 12_937_772, "d24fb083ae3b1041fc24e97971d60e280c9342201fbb67b0ab428a8b4a51a434"),
        asrFile("言語の識別（Whisper tiny）decoder", "$WHISPER/tiny-decoder.int8.onnx", "whisper-tiny/tiny-decoder.int8.onnx", 89_855_401, "d2fece8dd42771f1df975c6c0445770d0c292bf7547c2cae04a6c0cc57540925"),
    )

    private const val QWEN_GGUF = "$HF/ggml-org/Qwen3-ASR-0.6B-GGUF/resolve/b98bf4381df3b400b929dcaff0dc83b6dafe221b"
    private fun gguf(name: String, size: Long, digest: String) = asrFile(name, "$QWEN_GGUF/$name", "qwen3-asr-gguf/$name", size, digest)
    val qwenWeights = gguf("Qwen3-ASR-0.6B-Q8_0.gguf", 804_749_536, "05c9e10cecb37f6fc0cac0e786195dba82f12c15021f0b8a09473dbf3b6f090d")
    val qwenProjector = gguf("mmproj-Qwen3-ASR-0.6B-Q8_0.gguf", 214_392_544, "3b5e371a03e7d14bc1b5a66f38ff3c5eec3a99b3f96deb72b3814da9adafb16d")
    val qwenGguf = listOf(qwenWeights, qwenProjector)

    fun recognition(model: SpeechModel): List<ModelFile> = when (model) {
        SpeechModel.SENSE_VOICE -> senseVoice
        SpeechModel.QWEN3_ASR -> qwenGguf
    }

    private fun asrFile(label: String, url: String, path: String, size: Long, sha256: String) =
        ModelFile(label = "音声認識（$label）", url = url, path = "asr/$path", size = size, sha256 = sha256)
}
