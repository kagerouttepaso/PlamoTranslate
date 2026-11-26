package app.plamotranslate.pipeline.translation

/** 呼ぶ側が訳を頼む1つの文（画面翻訳の1ブロック、カメラ翻訳の1段落、会話の1発話）。[id] はストリームの中で一意に呼ぶ側が決める。 */
data class Segment(val id: Long, val text: String)

/** [TranslationStream] が流す、文ごとの結果。 */
sealed interface SegmentUpdate {
    val id: Long

    /** 訳している途中の訳（その時点までの全体）。 */
    data class Partial(override val id: Long, val text: String, val lineComplete: Boolean = false) : SegmentUpdate

    /**
     * 確定した訳。[cached] はキャッシュから返したか。[millis] は頼んでから確定までの時間、[tokens] はおおよその生成トークン数
     * （キャッシュから返したときは0）。[computeWaitMillis] は依頼が未確定の間に、このストリームが貸出を待った時間。
     */
    data class Final(
        override val id: Long,
        val text: String,
        val cached: Boolean = false,
        val millis: Long = 0,
        val tokens: Int = 0,
        val computeWaitMillis: Long = 0,
    ) : SegmentUpdate

    /** 訳せなかった（翻訳モデルが読めない・推論の失敗など）。 */
    data class Failed(override val id: Long, val message: String) : SegmentUpdate
}
