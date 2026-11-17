package app.plamotranslate.pipeline.speech

/**
 * 判定：音声のスコア（候補の言語ごとの確率、[SpeechRecognizer.scores]）から、誰が話しているか（話し手の言語）を決める
 * （docs/設計/会話翻訳.md「カードの区切り方」）。会話の話し手は自分と相手の2人なので、
 * 自分の言語（[SpeechLanguages.primary]）か、それ以外かで比べる。それ以外の確率は、自分以外の言語の確率の合計にする
 * （相手がまだ分からない間は候補が多く、1つの言語の確率は小さくなるため）。それ以外なら、そのうち一番の言語を返す。
 * 相手が分かれば候補は自分と相手の2つなので、同じ決まりがそのまま2択になる。
 *
 * 確率が [confidence] に届かない音声は決めない（null）。短い文・雑音・あいづちは、前の話し手のまま扱う。
 * 短い区間ほど外れやすいので、話し中の区間は [WINDOW] で、文の全体は [PHRASE] で決める（PC で、作者の日本語の頭の1.5秒を
 * 韓国語 0.95〜0.99 と判定した区間があった。文の全体では日本語 0.99）。
 */
object SpeakerJudge {
    /** 話し中の区間（1.5秒）で話し手を決める確率のしきい値。 */
    const val WINDOW = 0.75f

    /** 文の全体で話し手を決める確率のしきい値。 */
    const val PHRASE = 0.6f

    fun decide(scores: Map<String, Float>?, languages: SpeechLanguages, confidence: Float): String? {
        if (scores.isNullOrEmpty()) return null
        val self = languages.primary
        if (self == null) {
            val top = scores.maxBy { it.value }
            return top.key.takeIf { top.value >= confidence }
        }
        val selfScore = scores[self] ?: 0f
        val other = scores.filterKeys { it != self }
        val otherScore = other.values.sum()
        return when {
            selfScore >= confidence && selfScore >= otherScore -> self
            otherScore >= confidence && other.isNotEmpty() -> other.maxBy { it.value }.key
            else -> null
        }
    }
}
