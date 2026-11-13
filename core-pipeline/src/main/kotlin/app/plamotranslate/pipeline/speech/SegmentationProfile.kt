package app.plamotranslate.pipeline.speech

/**
 * 区切り方の値の組（docs/設計/会話翻訳.md「カードの区切り方」）。用途（会話・テキスト翻訳の音声入力）ごとに組を選び、
 * [SpeechSegmenter] と VAD（[VoiceActivity] の実装）に渡す。if 文で用途を分けない。
 */
data class SegmentationProfile(
    /**
     * 話し終わりとみなす無音の長さ（秒）。文の終わりの無音を含む。途中の訳が見えるので確定を急がず、
     * 言いよどみや息継ぎでカードが切れないほうを選んだ（`docs/判断/2026-11-06_カードを話し手の交代でも区切る.md`）。
     */
    val endSilenceSeconds: Float = 2.0f,
    /** 文の終わりとみなす無音（VADの区切り）。言いよどみより長く、話し手の交代を拾える長さ。 */
    val phraseSilenceSeconds: Float = 0.6f,
    /**
     * VADが話し始めを検出するまでの遅れを補うため、話し始めの頭に足す直前の音声の長さ（秒）。作者の会話の録音で、
     * 検出が実際の話し始めより0.7〜1.3秒遅れ、0.5秒では言い始めの音が入らなかった（「フォークした」が「ォクした」）。
     * 話し終わりの後に聞き直した音声だけなので、前のカードの音は入らない。言語の判定の窓は、足した分の後ろから測る。
     */
    val prerollSeconds: Float = 1.5f,
    /** 長く話し続けたら、この長さ（秒）たまった時点で、直近の静かなところで区切る。 */
    val splitAfterSeconds: Float = 14f,
    /**
     * 話している最中に、直近の言語が何回続けてカードの言語と違ったら話し手が代わったとみなすか（0.5秒ごとに見る）。
     * 区切るボタンで人が区切れるので、自動の区切りは粘る（1枚にまとまる）方へ寄せる。
     */
    val liveSwitchChecks: Int = 3,
) {
    companion object {
        /** 会話翻訳。テキスト翻訳の音声入力も、今は同じ組を使う（最初の確定で止めるのは呼ぶ側）。 */
        val CONVERSATION = SegmentationProfile()
    }
}
