package app.plamotranslate.pipeline.speech

import app.plamotranslate.pipeline.speech.SpeechEngine.Companion.SAMPLE_RATE

/**
 * カードをまたぐ話し手：前のカードの話し手を覚え、話し手を決められない音声に使う言語を決める（docs/設計/会話翻訳.md
 * 「カードの区切り方」）。1枚のカードの中の話し手は [SpeakerTrack] が決め、ここはカードの間だけを扱う。
 * 区切って開いた次のカードの話し手（交代で区切った後ろの話し手・長い発話で区切った同じ話し手）は、区切った理由ごとに
 * [SpeechSegmenter] が [SpeakerTrack.assign] で渡す。
 */
internal class SpeakerHandoff {
    /** 直前のカードの話し手の言語。 */
    private var previous: String? = null

    /** 話し手が [lang] のカードが終わった（区切った・確定した）。決まっていなければ前の話し手のまま。 */
    fun ended(lang: String?) {
        previous = lang ?: previous
    }

    /** 決められない音声に使う言語：カードの言語 [cardLang]、前の話し手、自分の言語の順。 */
    fun fallback(cardLang: String?, languages: SpeechLanguages): String? = cardLang ?: previous ?: languages.primary

    /**
     * 話し始めから [spoken] サンプル話しても話し手の決まらないカード（[track]）に、仮に使う言語。まだ待つなら null。
     * 決まらないと途中結果を出せないので、[CARD_LANG_MAX_SAMPLES] 話したら前の話し手の言語を仮に使う。
     */
    fun provisional(track: SpeakerTrack, spoken: Int, languages: SpeechLanguages): String? =
        if (track.lang == null && spoken >= CARD_LANG_MAX_SAMPLES) fallback(null, languages) else null

    /** 聞き直す。 */
    fun reset() {
        previous = null
    }

    private companion object {
        /** 話し始めからこの長さ話してもカードの言語が決まらなければ、前の話し手の言語を仮に使う。 */
        const val CARD_LANG_MAX_SAMPLES = SAMPLE_RATE * 5
    }
}
