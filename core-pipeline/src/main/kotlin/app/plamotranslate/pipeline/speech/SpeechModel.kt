package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language

/**
 * 音声認識モデルの一覧（docs/作業中/会話翻訳に守備範囲の広い音声認識を足す.md）。設定の画面で前もって選び、
 * 会話翻訳とテキスト翻訳の音声入力が使う。会話で選べる言語は「モデルが聞ける」かつ「PLaMo が訳せる」言語（[languages]）。
 *
 * 言語の識別は2層にする。[identifiesLanguage] が true のモデルは、モデル自身の言語の判定を使う（書き起こした言語を点数1とする）。
 * false のモデルには、既定の言語の識別（[LanguageIdentifier]、Whisper tiny）を前に置き、決めた話し手の言語を指定して書き起こさせる。
 * どのモデルも、区切りは文ごとに話し手を決めて書き起こし、つなぐ（[SpeechSegmenter]）。
 *
 * [heavy] が true のモデルは書き起こしが重い（1回に1〜2秒）。書き起こしの時間は渡す音声の長さに比例するので、話し始めの前に
 * 足した音声のうち、声の始まりより前の無音は渡さない（[SpeechOnset]）。速いモデルは足した音声を全部渡す。
 */
enum class SpeechModel(
    val label: String,
    val note: String,
    val languages: List<Language>,
    val identifiesLanguage: Boolean,
    val heavy: Boolean,
) {
    SENSE_VOICE(
        "SenseVoice Small",
        "日・韓・英・中の5言語。速い（5秒の発話を約0.15秒で書き起こす）",
        listOf(Language.JA, Language.KO, Language.EN, Language.ZH, Language.ZH_HANT),
        identifiesLanguage = true,
        heavy = false,
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
        heavy = true,
    ),
    ;

    companion object {
        val DEFAULT = SENSE_VOICE
    }
}

/** インターフェース：話された言語を見分ける（認識モデルに依らない既定の層）。実装は `core-asr`（Whisper tiny）。 */
interface LanguageIdentifier : AutoCloseable {
    /**
     * 候補の言語（[candidates]、"ja"・"ru" などのコード。null なら識別器が知るすべての言語）ごとの確率（合計1）。
     * 判定できなければ null。どちらの言語に近いかを比べるので、一番の言語の名前ではなく点数を返す。
     */
    fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>?
}

/**
 * 処理のブロックをつなぐ：言語の識別（会話の言語ごとの点数）と、決めた言語を指定した書き起こし。
 * 識別は会話の言語（[SpeechLanguages.groups]）の中で比べる。違う言語を指定すると書き起こしが崩れる（端末で、アラビア語を
 * トルコ語と誤って指定するとローマ字になった）ので、会話の言語の外は選ばない。
 */
class IdentifiedRecognizer(
    private val identifier: LanguageIdentifier,
    private val recognizer: SpeechRecognizer,
) : SpeechRecognizer {
    override fun recognize(samples: FloatArray): Recognition = recognize(samples, SpeechLanguages.ANY)

    override fun recognize(samples: FloatArray, languages: SpeechLanguages): Recognition = recognize(samples, languages, null)

    /**
     * 言語を指定せずに書き起こすとき（テキスト翻訳の音声入力など）は、識別して一番の言語を指定する。判定できなければ
     * [fallback] として書き起こす。言語を指定しないと、Qwen3-ASR が中国語で書くことがある。
     */
    override fun recognize(samples: FloatArray, languages: SpeechLanguages, fallback: String?): Recognition {
        val groups = languages.groups
        val lang = SpeakerJudge.decide(scores(samples, languages), languages.copy(primary = null), 0f)
            ?: fallback?.takeIf { groups == null || it in groups }
        return recognizeAs(samples, lang, groups)
    }

    /** 言語が分かっているとき（話し手の言語）。識別を省いて、その言語として書き起こす。 */
    override fun recognizeAs(samples: FloatArray, language: String?): Recognition = recognizeAs(samples, language, null)

    private fun recognizeAs(samples: FloatArray, lang: String?, groups: Set<String>?): Recognition {
        val r = recognizer.recognizeAs(samples, lang)
        return r.copy(lang = lang ?: ScriptLanguage.guess(r.text, groups))
    }

    override val identifies: Boolean get() = true

    /** 識別器の点数を、比べるための組（中国語・広東語は1つの組）にまとめる。 */
    override fun scores(samples: FloatArray, languages: SpeechLanguages): Map<String, Float>? {
        val raw = identifier.scores(samples, languages.groups) ?: return null
        val grouped = HashMap<String, Float>()
        for ((lang, p) in raw) {
            val group = LanguageEvidence.group(lang, separateChinese = true) ?: continue
            grouped[group] = (grouped[group] ?: 0f) + p
        }
        return grouped
    }

    override fun close() {
        identifier.close()
        recognizer.close()
    }
}
