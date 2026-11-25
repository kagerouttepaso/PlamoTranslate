package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

enum class ComputeWork { Regular, FinalTranscript }

class ComputeScheduler(
    private val onGranted: (CpuTopology, Boolean, Int, Long) -> Unit = { _, _, _, _ -> },
) {
    private class Ticket(val gpu: Boolean, val work: ComputeWork, val requested: Int) {
        val ready = CompletableDeferred<Unit>()
        lateinit var topology: CpuTopology
        var granted = 0
    }

    private val waiting = mutableListOf<Ticket>()
    private val active = mutableSetOf<Ticket>()
    private lateinit var latest: CpuTopology

    // 優先順位は資源ごとに決める。先頭の依頼が入るまでは小さい後続の依頼も待つ。
    private fun grant(): List<Ticket> = buildList {
        val budget = latest.budget
        for (gpu in listOf(false, true)) {
            while (true) {
                val queue = waiting.filter { it.gpu == gpu }
                val next = queue.firstOrNull { it.work == ComputeWork.FinalTranscript } ?: queue.firstOrNull() ?: break
                val count = if (gpu) next.requested else minOf(next.requested, budget)
                if (gpu && active.any { it.gpu }) break
                val cpu = active.filterNot { it.gpu }
                if (!gpu && (cpu.any { it.requested == Int.MAX_VALUE } ||
                        cpu.sumOf { it.granted.toLong() } + count > budget)) break
                waiting.remove(next)
                next.granted = count
                next.topology = latest
                active.add(next)
                add(next)
            }
        }
    }

    suspend fun <T> borrow(topology: CpuTopology, gpu: Boolean, requested: Int, work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T): T {
        require(requested >= 0)
        val start = System.nanoTime()
        val demand = if (requested == 0) topology.candidates().first() else requested
        val ticket = Ticket(gpu, work, if (gpu || demand == Int.MAX_VALUE) demand else minOf(demand, topology.budget))
        try {
            synchronized(waiting) {
                latest = topology
                waiting.add(ticket)
                grant()
            }.forEach { it.ready.complete(Unit) }
            ticket.ready.await()
            currentCoroutineContext().ensureActive()
            onGranted(ticket.topology, gpu, ticket.granted, System.nanoTime() - start)
            return action(ticket.granted) { synchronized(waiting) { waiting.any { it.gpu == gpu } } }
        } finally {
            synchronized(waiting) {
                waiting.remove(ticket)
                active.remove(ticket)
                grant()
            }.forEach { it.ready.complete(Unit) }
        }
    }
}
