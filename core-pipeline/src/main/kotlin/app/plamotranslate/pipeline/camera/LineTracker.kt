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

/**
 * 1コマで読めた1行（正立した画像の座標）。[look] は描き方（色など）で、中核は中身を見ずに預かる。
 * [sharpness] はその行の画像の鋭さ（[Sharpness]）。測っていなければ null で、ほかの読み取りと同じ重みにする。
 */
class Reading<L>(val box: Box, val text: String, val look: L, val sharpness: Double? = null)

/**
 * コマをまたいで同じ行を追い、多数決で読み取りを安定させる（docs/設計/カメラ翻訳.md「追跡と多数決」）。
 * 追う行には番号（[Track.id]）を付ける。段落の番号（[CameraParagraph.objectId]）はこの番号から受け継ぐ。
 *
 * - 各コマの行を、前のコマの位置が重なる行（IoU [MATCH_IOU] 以上）、なければ近くにある同じ内容の行に結び付ける。
 * - 行ごとに直近 [READINGS] 回の読み取りを持ち、文字ごとの多数決で原文を決める（[Track.consensus]・[ReadingFusion]）。
 *   手ぶれ・ピンぼけのコマの読み取りは、票を軽くする（[Track.weights]）。
 * - 読めなかったコマが [KEEP_MISSED] 回までなら行を残す（1コマの読み落としで枠がちらつかないように）。
 *   ただし、そのコマで読めた行に重なる古い行は、カメラが動いてずれた枠なので消す。
 */
class LineTracker<L> {
    private val tracks = mutableListOf<Track<L>>()
    private var nextId = 1L

    fun clear() = tracks.clear()

    /** 追跡が続いている行の番号（見えていなくても、まだ捨てていない行）。 */
    fun aliveIds(): Set<Long> = tracks.mapTo(HashSet()) { it.id }

    /** このコマの読み取りを、前のコマまでの行に結び付ける。読み取りを足した行を返す。 */
    fun update(readings: List<Reading<L>>): List<Track<L>> {
        tracks.forEach { it.visible = false }
        val free = tracks.toMutableList()
        val updated = mutableListOf<Track<L>>()
        for (r in readings) {
            val k = key(r.text)
            val match = free.maxByOrNull { iou(it.box, r.box) }?.takeIf { iou(it.box, r.box) >= MATCH_IOU }
                ?: free.firstOrNull { similar(key(it.consensus()), k) && near(it.box, r.box) }
            val track = match?.also { free.remove(it) } ?: Track<L>(nextId++).also { tracks += it }
            track.add(r)
            updated += track
        }
        for (t in free) t.missed++
        val seen = tracks.filter { it.missed == 0 }
        tracks.removeAll { t -> t.missed > KEEP_MISSED || (t.missed > 0 && seen.any { it.box.intersects(t.box) }) }
        tracks.filter { it.missed in 1..KEEP_MISSED }.forEach { it.visible = true }
        return updated
    }

    /**
     * 今見えている行を、上から順に返す。[includeUnsure] が false なら、[AGREE] 回以上読めた行だけ。
     * 同じ行が二重に読めた枠（小さい方の5割以上が重なる。認識器ごとに少しずれた枠ができる）は、
     * 読み取りが確かな方（このコマで読めた・多数決の原文とほぼ同じ読み取りの重みの合計が大きい）だけを残す。
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
        private val sharpness = ArrayDeque<Double?>()
        private val heights = ArrayDeque<Int>()
        private var cached: String? = null
        private var weighted: List<Double>? = null

        fun add(r: Reading<L>) {
            box = r.box
            look = r.look
            visible = true
            missed = 0
            count++
            readings.addLast(r.text)
            sharpness.addLast(r.sharpness)
            heights.addLast(r.box.height)
            if (readings.size > READINGS) {
                readings.removeFirst()
                sharpness.removeFirst()
                heights.removeFirst()
            }
            cached = null
            weighted = null
            fused = null
            agreed = null
        }

        private var fused: ReadingFusion.Fused? = null
        private var fusedWeight = 0.0
        private var agreed: Double? = null

        /**
         * 読み取りごとの鋭さの割合（0〜1。古い順）。同じ行の直近の読み取りのうち、行の高さが近い（[HEIGHT_RANGE] 倍以内）
         * コマでいちばん鋭かったものに対する割合。「その行がいちばんよく見えたときにどれだけ近いか」で、手ぶれが続いて鋭いコマが
         * 少ないときも、鋭いコマを基準にする。鋭さの絶対の大きさは書体や文字の詰まり方で行ごとに違い、写る大きさでも少し変わるので、
         * 同じ行の、同じくらいの大きさに写ったコマどうしでだけ比べる。鋭さを測れなかった読み取りは null。
         */
        fun ratios(): List<Double?> = sharpness.mapIndexed { i, s ->
            if (s == null) return@mapIndexed null
            val h = heights[i].coerceAtLeast(1)
            val best = sharpness.withIndex().filter { (j, o) ->
                val hj = heights[j].coerceAtLeast(1)
                o != null && maxOf(h, hj) <= minOf(h, hj) * HEIGHT_RANGE
            }.maxOf { it.value!! }
            if (best <= 0.0) null else s / best
        }

        /**
         * 読み取りごとの票の重み（[MIN_WEIGHT]〜1。古い順）。鋭さの割合（[ratios]）が [SHARP_ENOUGH] 以上なら1
         * （手ぶれの無いコマどうしでも、鋭さはコマごとに少し揺れるので、その差では軽くしない）、それより低ければ割合に比例して軽くする。
         * 鋭さを測れなかった読み取りは1（ほかの読み取りと同じ）。
         */
        fun weights(): List<Double> = weighted ?: ratios().map { r ->
            if (r == null) 1.0 else (r / SHARP_ENOUGH).coerceIn(MIN_WEIGHT, 1.0)
        }.also { weighted = it }

        /** 直近の読み取りの重みが [BLURRED] 未満か（ぶれたコマとして軽くした。ログに数を出す）。 */
        fun lastBlurred(): Boolean = weights().lastOrNull()?.let { it < BLURRED } ?: false

        /**
         * 原文。読み取りが [FUSE_FROM] 回以上あれば、ほかといちばん似ている読み取りを土台に、文字ごとの多数決で合わせた文
         * （[ReadingFusion]）。読み続けるほど、崩れた読み取りが混じっても正しい文になる。それより少なければ、ほかといちばん
         * 似ている読み取りをそのまま使う。「ほかといちばん似ている」も票も、読み取りの重み（[weights]）を掛けて比べる。
         * 読み取りが偶数回なら、いちばん軽い1回（同じ重みなら古い方）を除いて奇数回にする（重みがそろっているときの同数の割れを
         * 減らす。いちばん軽い1回を除くので、鋭い読み取りは多数決から外れない）。
         */
        fun consensus(): String = cached ?: run {
            val all = weights()
            val drop = if (readings.size >= FUSE_FROM && readings.size % 2 == 0) all.indices.minBy { all[it] } else -1
            val list = readings.filterIndexed { i, _ -> i != drop }
            val w = all.filterIndexed { i, _ -> i != drop }
            val keys = list.map(::key)
            val best = list.indices.maxByOrNull { i ->
                w[i] * keys.indices.sumOf { j -> if (i == j) 0.0 else w[j] * dice(keys[i], keys[j]) } + i * 1e-6
            } ?: 0
            fusedWeight = w.sum()
            if (list.size >= FUSE_FROM) ReadingFusion.fuse(list, best, w).also { fused = it }.text else list[best]
        }.also { cached = it }

        /** 多数決で採った原文と、ほぼ同じ読み取りの数（自分を含む。読み取りの重みの合計）。 */
        fun agreement(): Double = agreed ?: run {
            val c = key(consensus())
            val w = weights()
            readings.withIndex().sumOf { (i, r) -> if (similar(key(r), c)) w[i] else 0.0 }
        }.also { agreed = it }

        /**
         * 読み取りがそろったか。ほぼ同じ読み取りが（重みの合計で）[AGREE] 回分あるか、多数決にかけた読み取りの重みの合計が
         * [AGREE] 回分以上あって、文字ごとの多数決の得票の割合（確かさ）が [SETTLED_VOTES] 以上か。
         * どの読み取りも別の位置が崩れていて互いに一致しなくても、多数決で合わせた文が確かなら、そろったとみなす。
         * 重みの合計の条件は、鋭い1回の票だけで（ぶれた読み取りの票が軽いため）確かさが高く出たときに、そろったとしないため。
         */
        fun settled(): Boolean {
            if (agreement() >= AGREE) return true
            consensus()
            return fusedWeight >= AGREE && (fused?.confidence ?: 0.0) >= SETTLED_VOTES
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

        /** 文字ごとの多数決を使い始める読み取りの数（2回では多数が決まらない）。 */
        const val FUSE_FROM = 3

        /** そろったとみなす、文字ごとの多数決の得票の割合（3回なら、ほとんどの文字で2票以上）。 */
        const val SETTLED_VOTES = 0.75

        /** 鋭さを比べる読み取りの、行の高さの比の上限（写る大きさが違うと、同じぶれ方でも鋭さの値が変わる）。 */
        const val HEIGHT_RANGE = 1.25

        /** いちばん鋭いコマに対して、この割合以上の鋭さなら重みを1にする。 */
        const val SHARP_ENOUGH = 0.7

        /** 読み取りの重みの下限（ぶれたコマでも、読めた文字は少しだけ数える）。 */
        const val MIN_WEIGHT = 0.1

        /** ぶれたコマとして数える重み（ログ用）。 */
        const val BLURRED = 0.5

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
