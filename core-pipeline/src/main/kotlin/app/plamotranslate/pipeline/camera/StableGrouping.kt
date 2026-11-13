package app.plamotranslate.pipeline.camera

/**
 * 段落の区切りを、直近のコマにわたって安定させる（docs/設計/カメラ翻訳.md「段落にまとめる」）。
 * コマごとのまとめ方（中核の `TextGrouper`・`MenuGrouper`）の結果をそのまま段落にすると、境目の行がコマのたびに
 * 付いたり離れたりし、段落の原文が変わって訳し直しと枠のちらつきが起きる（2026-11-13、作者の指摘「つながるか
 * つながらないか微妙な文字」）。
 *
 * 区切り全体を1つの状態として持つ。今の安定した段落ごとに、そのコマのまとめ方と顔ぶれが違ったか（見えている行が
 * 1つの段落に入り、その段落にほかの安定した段落の行が混じっていないか）を直近 [WINDOW] コマ数え、違ったコマが
 * [FLIP_TENTHS] 割を超え [MIN_FLIP] コマ以上になったときだけ、その段落をそのコマのまとめ方の段落で置き換える（ヒステリシス）。
 * 置き換えに使うのは必ずどこかのコマのまとめ方の段落（の一部）なので、まとめ方の規則の約束（文章の行数の上限、
 * 番号・記号で始まる行での区切り、表・メニューの2行まで）は崩れない。
 *
 * 新しく見えた行は、そのコマのまとめ方で、ほかの段落と顔ぶれが合う安定した段落に1つだけ当てはまれば、すぐそこに入れる
 * （段落が伸びたとき）。当てはまらなければ、そのコマの段落のうち新しい行だけで段落を作る。
 */
class StableGrouping {
    private class Group(val members: LinkedHashSet<Long>) {
        /** 直近のコマで、まとめ方と顔ぶれが違ったか（true が違った）。 */
        val disagreed = ArrayDeque<Boolean>()
    }

    private val stable = mutableListOf<Group>()

    fun clear() = stable.clear()

    /**
     * 1コマの観測を足す。[units] はそのコマのまとめ方の段落（行の番号の並び）、[visible] はそのコマで見えている行の番号、
     * [alive] は追跡が続いている行の番号（見えていなくても、まだ捨てていない行）。追跡の切れた行は忘れる。
     */
    fun observe(units: List<List<Long>>, visible: Set<Long>, alive: Set<Long>) {
        stable.forEach { it.members.retainAll(alive) }
        stable.removeAll { it.members.isEmpty() }
        val frame = units.map { u -> u.filterTo(LinkedHashSet()) { it in visible } }.filter { it.isNotEmpty() }
        val unitOf = HashMap<Long, Int>()
        frame.forEachIndexed { i, u -> u.forEach { unitOf[it] = i } }
        val owner = HashMap<Long, Group>()
        stable.forEach { g -> g.members.forEach { owner[it] = g } }

        // 新しく見えた行を、顔ぶれの合う段落へ入れるか、新しい段落にする
        frame.forEachIndexed { i, u ->
            val fresh = u.filter { it !in owner }
            if (fresh.isEmpty()) return@forEachIndexed
            val target = u.mapNotNull { owner[it] }.toSet().singleOrNull()
                ?.takeIf { g -> g.members.filter { it in visible }.all { unitOf[it] == i } }
            val g = target ?: Group(LinkedHashSet()).also { stable += it }
            g.members += fresh
            fresh.forEach { owner[it] = g }
        }

        // 段落ごとに、このコマのまとめ方と顔ぶれが違ったかを数え、違い続けた段落を選ぶ
        val flipped = stable.filter { g ->
            val seen = g.members.filter { it in visible }
            if (seen.isEmpty()) return@filter false
            val units = seen.map { unitOf.getValue(it) }.toSet()
            val agree = units.size == 1 && frame[units.single()].all { owner[it] === g }
            g.disagreed.addLast(!agree)
            if (g.disagreed.size > WINDOW) g.disagreed.removeFirst()
            val n = g.disagreed.count { it }
            n >= MIN_FLIP && n * 10 > g.disagreed.size * FLIP_TENTHS
        }

        // 違い続けた段落を、このコマのまとめ方の段落で置き換える（ほかの段落の行も、その段落ごと移す）
        for (g in flipped) {
            if (g !in stable) continue
            val seen = g.members.filter { it in visible }
            val hidden = g.members.filterNot { it in visible }
            stable.remove(g)
            for (i in seen.map { unitOf.getValue(it) }.toSet()) {
                val u = frame[i]
                stable.forEach { it.members.removeAll(u) }
                stable += Group(LinkedHashSet(u))
            }
            if (hidden.isNotEmpty()) stable += Group(LinkedHashSet(hidden))
            stable.removeAll { it.members.isEmpty() }
        }
    }

    /** 今の区切りで、[lines] の行を段落に分ける（段落の中の行の並べ方は呼ぶ側が決める）。区切りに無い行は1行の段落。 */
    fun groups(lines: Collection<Long>): List<List<Long>> {
        val wanted = lines.toHashSet()
        val grouped = stable.map { g -> g.members.filter { it in wanted } }.filter { it.isNotEmpty() }
        val covered = grouped.flatten().toHashSet()
        return grouped + lines.filterNot { it in covered }.map { listOf(it) }
    }

    companion object {
        /** 数える直近のコマの数（時間ではなくコマの数で数える。読む間隔が延びれば、切り替わりも遅れる）。 */
        const val WINDOW = 6

        /** 違ったコマが、数えたコマのこの割合（10分の1単位）を超えたら切り替える。 */
        const val FLIP_TENTHS = 6

        /** 切り替えるのに要る、違ったコマの最小の数（1〜2コマの揺れでは切り替えない）。 */
        const val MIN_FLIP = 3
    }
}
