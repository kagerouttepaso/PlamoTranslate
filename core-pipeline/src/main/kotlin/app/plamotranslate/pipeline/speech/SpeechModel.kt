package app.plamotranslate.pipeline.speech

import app.plamotranslate.llm.Language

/**
 * 音声認識モデルの一覧（docs/設計/会話翻訳.md「エンジンと出来事」）。設定の画面で前もって選び、
 * 会話翻訳とテキスト翻訳の音声入力が使う。会話で選べる言語は「モデルが聞ける」かつ「PLaMo が訳せる」言語（[languages]）。
 *
 * 言語の識別は2層にする。[identifiesLanguage] が true のモデルは、モデル自身の言語の判定を使う（書き起こした言語をスコア1とする）。
 * false のモデルには、既定の言語の識別（[LanguageIdentifier]、Whisper tiny）を前に置き、決めた話し手の言語を指定して書き起こさせる。
 * どのモデルも、セグメンター（[SpeechSegmenter]）が文ごとに話し手を決めて書き起こし、つなぐ。
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
        "21言語。話し中は Whisper tiny が言語を識別し、文が終わると書き起こしでも言語を判定する",
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
     * 判定できなければ null。どちらの言語に近いかを比べるので、一番の言語の名前ではなくスコアを返す。
     */
    fun scores(samples: FloatArray, candidates: Set<String>?): Map<String, Float>?
}

/**
 * 処理のブロックをつなぐ：言語の識別（会話の言語ごとのスコア）と、決めた言語を指定した書き起こし。
 * 識別は会話の言語（[SpeechLanguages.groups]）の中で比べる。違う言語を指定すると書き起こしが崩れる（端末で、アラビア語を
 * トルコ語と誤って指定するとローマ字になった）ので、会話の言語の外は選ばない。
 */
class IdentifiedRecognizer(
    private val identifier: LanguageIdentifier,
    private val recognizer: SpeechRecognizer,
) {
    /**
     * 言語を指定せずに書き起こすとき（テキスト翻訳の音声入力など）は、識別して一番の言語を指定する。判定できなければ
     * [fallback] として書き起こす。言語を指定しないと、Qwen3-ASR が中国語で書くことがある。
     */
    suspend fun identifyAndRecognize(samples: FloatArray, languages: SpeechLanguages, fallback: String? = null, priority: RecognitionPriority): Recognition {
        val groups = languages.groups
        val identified = SpeakerJudge.decide(identifier.groupedScores(samples, languages), languages.copy(primary = null), 0f)
        if (identified != null) return recognizeAs(samples, identified, groups, LanguageSource.JUDGED, priority)
        return recognizeAs(samples, fallback?.takeIf { groups == null || it in groups }, groups, LanguageSource.SPECIFIED, priority)
    }

    /** [lang] として書き起こす（[source] はその言語の出どころ）。言語が無ければ指定せずに書き起こし、文字の種類から推定する。 */
    private suspend fun recognizeAs(samples: FloatArray, lang: String?, groups: Set<String>?, source: LanguageSource, priority: RecognitionPriority): Recognition {
        val r = recognizer.transcribe(samples, lang, priority)
        return if (lang != null) r.copy(lang = lang, source = source) else r.copy(lang = ScriptLanguage.guess(r.text, groups), source = LanguageSource.GUESSED)
    }

}

internal fun LanguageIdentifier.groupedScores(samples: FloatArray, languages: SpeechLanguages): Map<String, Float>? {
    val raw = scores(samples, languages.groups) ?: return null
    val grouped = HashMap<String, Float>()
    for ((lang, p) in raw) {
        val group = LanguageEvidence.group(lang, separateChinese = true) ?: continue
        grouped[group] = (grouped[group] ?: 0f) + p
    }
    return grouped
}
