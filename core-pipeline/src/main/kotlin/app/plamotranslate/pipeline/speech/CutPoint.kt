package app.plamotranslate.pipeline.speech

import kotlin.math.sqrt

/**
 * 判定：話し続けている音声を、どこで区切るか（docs/設計/会話翻訳.md「カードの区切り方」）。
 * [SpeechSegmenter] が区切ると決めた後に、範囲の中で区切る位置を選ぶ。区切った前は確定し、後ろは次のカードの頭に残す。
 * 区切る理由ごとに入口を分け、片方の選び方を替えてももう片方は変わらないようにする。
 */
object CutPoint {
    /** 音量を比べる区間の長さ（0.1秒）。 */
    const val FRAME_SAMPLES = SpeechEngine.SAMPLE_RATE / 10

    /** 長い発話の区切り：[from]〜[to] で、0.1秒ごとの音量が一番小さい区間の中央（息継ぎや語の切れ目は音が小さい）。 */
    fun longUtterance(buffer: SpeechBuffer, from: Int, to: Int): Int = quietest(buffer, from, to)

    /** 話し手の交代の区切り：言語の継ぎ目の前後 [from]〜[to] で、0.1秒ごとの音量が一番小さい区間の中央。 */
    fun languageSeam(buffer: SpeechBuffer, from: Int, to: Int): Int = quietest(buffer, from, to)

    /** ためた音声の [from]〜[to] で、[FRAME_SAMPLES] ごとの音量（二乗平均平方根）が一番小さい区間の中央を、ためた音声の位置で返す。 */
    internal fun quietest(buffer: SpeechBuffer, from: Int, to: Int): Int {
        val start = from.coerceIn(0, buffer.size)
        val end = to.coerceIn(start, buffer.size)
        if (end - start < FRAME_SAMPLES) return end
        val audio = buffer.slice(start, end)
        var best = FRAME_SAMPLES / 2
        var bestRms = Double.MAX_VALUE
        var i = 0
        while (i + FRAME_SAMPLES <= audio.size) {
            var sum = 0.0
            for (j in i until i + FRAME_SAMPLES) sum += audio[j] * audio[j]
            val rms = sqrt(sum / FRAME_SAMPLES)
            if (rms < bestRms) {
                bestRms = rms
                best = i + FRAME_SAMPLES / 2
            }
            i += FRAME_SAMPLES / 2
        }
        return start + best
    }
}
