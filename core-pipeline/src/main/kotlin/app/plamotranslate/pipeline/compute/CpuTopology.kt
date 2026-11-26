package app.plamotranslate.pipeline.compute

class CpuTopology(cores: Map<Int, Core?>) {
    data class Core(val group: Int, val performance: Long)

    val budget = cores.size
    init { require(budget > 0) }

    // 各段には許された CPU だけを入れる。同速の段は最小の CPU 番号で順序を固定する。
    val tiers: List<List<Int>> = if (cores.containsValue(null)) {
        listOf(cores.keys.sorted())
    } else {
        cores.keys.groupBy { cores.getValue(it)!!.group }.values
            .sortedWith(compareByDescending<List<Int>> { group ->
                group.maxOf { cores.getValue(it)!!.performance }
            }.thenBy { it.min() })
            .map { it.sorted() }
    }

    val key = tiers.joinToString("/", prefix = "v2:") { it.joinToString(",") }

    /**
     * 試す数の候補。[requested] が1以上なら、その数だけ（明示設定）。0なら、速い段から順に足した数。
     * [capacity] は部品が使える数の上限、[reserved] は同時に走る別の部品が借りる数で、候補は予算からそれを引いた数（最低1）までにする。
     */
    fun candidates(requested: Int = 0, capacity: Int = Int.MAX_VALUE, reserved: Int = 0): List<Int> {
        if (requested > 0) return listOf(requested.coerceAtMost(budget))
        val limit = minOf(capacity, maxOf(1, budget - reserved))
        return tiers.map { it.size }.runningFold(0, Int::plus).drop(1).map { minOf(it, limit) }.distinct()
    }
}
