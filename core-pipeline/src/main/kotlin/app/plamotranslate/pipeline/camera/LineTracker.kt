package app.plamotranslate.pipeline.camera

import app.plamotranslate.pipeline.camera.TextSimilarity.dice
import app.plamotranslate.pipeline.camera.TextSimilarity.key
import app.plamotranslate.pipeline.camera.TextSimilarity.similar
import app.plamotranslate.pipeline.layout.centerX
import app.plamotranslate.pipeline.layout.centerY
import app.plamotranslate.pipeline.layout.height
import app.plamotranslate.pipeline.layout.intersects
import app.plamotranslate.pipeline.layout.overlaps
import app.plamotranslate.pipeline.layout.width
import app.plamotranslate.pipeline.screen.Box
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 1コマで読めた1行（正立した画像の座標）。[look] は描き方（色など）で、中核は中身を見ずに預かる。 */
class Reading<L>(val box: Box, val text: String, val look: L)

/**
 * コマをまたいで同じ行を追い、多数決で読み取りを安定させる（docs/設計/カメラ翻訳.md「追跡と多数決」）。
 * 追う行には番号（[Track.id]）を付ける。段落の番号（[CameraParagraph.objectId]）はこの番号から受け継ぐ。
 *
 * - 各コマの行を、前のコマの位置が重なる行（IoU [MATCH_IOU] 以上）、なければ近くにある同じ内容の行に結び付ける。
 * - 行ごとに直近 [READINGS] 回の読み取りを持ち、ほかの読み取りといちばん似ているものを原文にする（[Track.consensus]）。
 * - 読めなかったコマが [KEEP_MISSED] 回までなら行を残す（1コマの読み落としで枠がちらつかないように）。
 *   ただし、そのコマで読めた行に重なる古い行は、カメラが動いてずれた枠なので消す。
 */
class LineTracker<L> {
    private val tracks = mutableListOf<Track<L>>()
    private var nextId = 1L

    fun clear() = tracks.clear()

    /** このコマの読み取りを、前のコマまでの行に結び付ける。 */
    fun update(readings: List<Reading<L>>) {
        tracks.forEach { it.visible = false }
        val free = tracks.toMutableList()
        for (r in readings) {
            val k = key(r.text)
            val match = free.maxByOrNull { iou(it.box, r.box) }?.takeIf { iou(it.box, r.box) >= MATCH_IOU }
                ?: free.firstOrNull { similar(key(it.consensus()), k) && near(it.box, r.box) }
            val track = match?.also { free.remove(it) } ?: Track<L>(nextId++).also { tracks += it }
            track.add(r)
        }
        for (t in free) t.missed++
        val seen = tracks.filter { it.missed == 0 }
        tracks.removeAll { t -> t.missed > KEEP_MISSED || (t.missed > 0 && seen.any { it.box.intersects(t.box) }) }
        tracks.filter { it.missed in 1..KEEP_MISSED }.forEach { it.visible = true }
    }

    /**
     * 今見えている行を、上から順に返す。[includeUnsure] が false なら、似た読み取りが [AGREE] 回そろった行だけ。
     * 同じ行が二重に読めた枠（小さい方の5割以上が重なる。認識器ごとに少しずれた枠ができる）は、
     * 読み取りが確かな方（このコマで読めた・多数決で同じ読み取りが多い）だけを残す。
     */
    fun lines(includeUnsure: Boolean): List<Track<L>> {
        val kept = mutableListOf<Track<L>>()
        tracks.filter { it.visible && (includeUnsure || it.count >= AGREE) }
            .sortedWith(compareBy<Track<L>> { it.missed }.thenByDescending { it.agreement() }.thenByDescending { it.count })
            .forEach { t -> if (kept.none { it.box.overlaps(t.box, 0.5f) }) kept += t }
        return kept.sortedBy { it.box.top }
    }

    /** コマをまたいで追う1行。直近の読み取りを持ち、多数決で原文を決める。 */
    class Track<L>(val id: Long) {
        var box = Box(0, 0, 0, 0)
            private set
        var look: L? = null
            private set
        var visible = false
        var missed = 0
        var count = 0
            private set
        private val readings = ArrayDeque<String>()
        private var cached: String? = null

        fun add(r: Reading<L>) {
            box = r.box
            look = r.look
            visible = true
            missed = 0
            count++
            readings.addLast(r.text)
            if (readings.size > READINGS) readings.removeFirst()
            cached = null
        }

        /** ほかの読み取りといちばん似ている読み取り（同点なら新しいもの）。 */
        fun consensus(): String = cached ?: run {
            val keys = readings.map(::key)
            val best = readings.indices.maxByOrNull { i ->
                keys.indices.sumOf { j -> if (i == j) 0.0 else dice(keys[i], keys[j]) } + i * 1e-6
            } ?: 0
            readings[best]
        }.also { cached = it }

        /** 多数決で採った読み取りと、ほぼ同じ読み取りの数（自分を含む）。 */
        fun agreement(): Int {
            val c = key(consensus())
            return readings.count { similar(key(it), c) }
        }
    }

    companion object {
        /** 行ごとに持つ読み取りの数。 */
        const val READINGS = 8

        /** 訳す・表示するのに要る、ほぼ同じ読み取りの数。 */
        const val AGREE = 2

        /** 読めなかったコマが、これを超えたら行を捨てる。 */
        const val KEEP_MISSED = 2

        /** 前のコマの行と同じとみなす重なり。 */
        const val MATCH_IOU = 0.3f

        /** 重なりの面積を、合わせた面積で割った値。 */
        private fun iou(a: Box, b: Box): Float {
            val w = min(a.right, b.right) - max(a.left, b.left)
            val h = min(a.bottom, b.bottom) - max(a.top, b.top)
            if (w <= 0 || h <= 0) return 0f
            val i = w.toFloat() * h
            val u = a.width.toFloat() * a.height + b.width.toFloat() * b.height - i
            return if (u <= 0f) 0f else i / u
        }

        /** 中心の距離が近いか（縦は行の高さの3倍、横はその2倍以内。カメラを少し動かしたとき）。 */
        private fun near(a: Box, b: Box): Boolean {
            val limit = maxOf(a.height, b.height) * 3
            return abs(a.centerX - b.centerX) <= limit * 2 && abs(a.centerY - b.centerY) <= limit
        }
    }
}
