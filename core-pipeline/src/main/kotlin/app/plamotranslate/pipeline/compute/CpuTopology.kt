package app.plamotranslate.pipeline.compute

class CpuTopology(cores: Map<Int, Long?>) {
    val cores = cores.toSortedMap().toMap()
    val budget = cores.size
    init { require(budget > 0) }
    val key = this.cores.entries.joinToString(",") { "${it.key}=${it.value ?: "?"}" }

    fun candidates(requested: Int = 0): List<Int> {
        if (requested > 0) return listOf(requested.coerceAtMost(budget))
        if (cores.containsValue(null)) return listOf(budget)
        return cores.values.filterNotNull().groupingBy { it }.eachCount()
            .toSortedMap(reverseOrder()).values.runningFold(0, Int::plus).drop(1)
    }
}
