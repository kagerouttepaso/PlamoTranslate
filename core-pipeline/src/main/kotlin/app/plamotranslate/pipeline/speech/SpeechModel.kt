package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language

/**
 * 音声認識モデルの一覧（docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md）。設定の画面で前もって選び、
 * 会話翻訳とテキスト翻訳の音声入力が使う。会話で選べる言語は「モデルが聞ける」かつ「PLaMo が訳せる」言語（[languages]）。
 *
 * 言語の識別は2層にする。[identifiesLanguage] が true のモデルは、モデル自身の言語の判定を使う（上の層の差し替え）。
 * false のモデルには、既定の言語の識別（[LanguageIdentifier]、Whisper tiny）を前に置き、識別した言語を指定して書き起こさせる。
 *
 * [byPhrase] が true のモデルは書き起こしが重い（1回に1〜2秒）。区切りは、カード全体を書き起こし直さず、
 * 文ごとに書き起こしてつなぐ（[SpeechSegmenter]）。
 */
enum class SpeechModel(
    val label: String,
    val note: String,
    val languages: List<Language>,
    val identifiesLanguage: Boolean,
    val byPhrase: Boolean,
) {
    SENSE_VOICE(
        "SenseVoice Small",
        "日・韓・英・中の5言語。速い（5秒の発話を約0.15秒で書き起こす）",
        listOf(Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT),
        identifiesLanguage = true,
        byPhrase = false,
    ),

    /** Qwen3-ASR は30言語を聞く。そのうち PLaMo が訳せる言語（ポルトガル語・トルコ語などは PLaMo に無い）。 */
    QWEN3_ASR(
        "Qwen3-ASR 0.6B",
        "21言語。遅い（5秒の発話に約1〜1.5秒）。言語は Whisper tiny で見分ける",
        listOf(
            Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT,
            Language.AR, Language.IT, Language.ID, Language.NL, Language.ES, Language.TH, Language.TL, Language.DE,
            Language.HU, Language.HI, Language.FR, Language.VI, Language.FA, Language.PL, Language.MS, Language.RU,
        ),
        identifiesLanguage = false,
        byPhrase = true,
    ),
    ;

    companion object {
        val DEFAULT = SENSE_VOICE
    }
}

/** 差し込み口：話された言語を見分ける（認識モデルに依らない既定の層）。実装は `core-asr`（Whisper tiny）。 */
interface LanguageIdentifier : AutoCloseable {
    /** 言語のコード（"ja"・"ru" など）。判定できなければ null。 */
    fun identify(samples: FloatArray): String?
}

/**
 * 処理のブロックをつなぐ：言語の識別 → 識別した言語を指定して書き起こす → 言語を確定する。
 * 識別は会話の言語（[SpeechLanguages.groups]）に絞り、外れたら書き起こした文の文字の種類で決める（[ScriptLanguage]）。
 * 違う言語を指定すると書き起こしが崩れる（端末で、アラビア語をトルコ語と誤って指定するとローマ字になった）ので、絞ってから渡す。
 */
class IdentifiedRecognizer(
    private val identifier: LanguageIdentifier,
    private val recognizer: SpeechRecognizer,
) : SpeechRecognizer {
    override fun recognize(samples: FloatArray): Recognition = recognize(samples, SpeechLanguages.ANY)

    override fun recognize(samples: FloatArray, languages: SpeechLanguages): Recognition {
        val groups = languages.groups
        val lang = identify(samples, languages)
        val r = recognizer.recognizeAs(samples, lang)
        return r.copy(lang = lang ?: ScriptLanguage.guess(r.text, groups))
    }

    override val identifies: Boolean get() = true

    override fun identify(samples: FloatArray, languages: SpeechLanguages): String? {
        val groups = languages.groups
        val identified = identifier.identify(samples)?.let { LanguageEvidence.group(it, separateChinese = true) }
        return identified?.takeIf { groups == null || it in groups }
    }

    override fun close() {
        identifier.close()
        recognizer.close()
    }
}
