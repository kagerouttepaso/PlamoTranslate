package app.plamotranslate.pipeline.camera

import app.plamotranslate.pipeline.camera.TextSimilarity.key
import app.plamotranslate.pipeline.camera.TextSimilarity.similar
import app.plamotranslate.pipeline.layout.centerX
import app.plamotranslate.pipeline.layout.centerY
import app.plamotranslate.pipeline.screen.Box
import kotlin.math.hypot

/** 訳す順番の決め方（[CameraSession] の訳す計画。docs/設計/カメラ翻訳.md「翻訳」）。 */
object TranslationPlan {
    /** 訳す候補の段落。[id] は段落の番号（[CameraParagraph.objectId]）、[settled] は読み取りがそろったか。 */
    data class Candidate(val id: Long, val text: String, val box: Box, val settled: Boolean)

    /**
     * [candidates] から、[needs] が true のものを [free] 件まで選ぶ。読み取りがそろった段落を先に、その中では画面の中央
     * （[screen] の中心。無ければ候補の並び順＝上から）に近い順にする。同じ文（キーがほぼ同じ）は1つにする。
     */
    fun choose(candidates: List<Candidate>, screen: Box?, free: Int, needs: (Candidate) -> Boolean): List<Candidate> {
        if (free <= 0) return emptyList()
        val byCenter = if (screen == null) candidates else candidates.sortedBy {
            hypot((it.box.centerX - screen.centerX).toDouble(), (it.box.centerY - screen.centerY).toDouble())
        }
        val ordered = byCenter.sortedBy { if (it.settled) 0 else 1 }
        val chosen = mutableListOf<Candidate>()
        for (c in ordered) {
            if (chosen.size >= free) break
            val k = key(c.text)
            if (k.isEmpty() || !needs(c) || chosen.any { similar(key(it.text), k) }) continue
            chosen += c
        }
        return chosen
    }
}
