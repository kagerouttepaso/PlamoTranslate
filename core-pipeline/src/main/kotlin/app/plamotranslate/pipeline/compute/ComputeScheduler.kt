package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job

enum class ComputePriority { Critical, Interactive, Normal, Speculative }
enum class StopMode { Finish, Cancel, Boundary }
enum class ResizeCost { Reload, Cheap }

data class ComputeWork(
    val priority: ComputePriority = ComputePriority.Normal,
    val stop: StopMode = StopMode.Finish,
    val resize: ResizeCost = ResizeCost.Reload,
    val name: String = priority.name,
) {
    companion object {
        val Regular = ComputeWork()
        val Translation = ComputeWork(stop = StopMode.Boundary, resize = ResizeCost.Cheap, name = "翻訳")
    }
}

class ComputeScheduler(private val log: (String) -> Unit = {}) {
    private class Ticket(val id: Long, val gpu: Boolean, val work: ComputeWork, val requested: Int, val job: Job) {
        var ready = CompletableDeferred<Unit>()
        var granted = 0
        lateinit var topology: CpuTopology
        var stoppedBy: Ticket? = null
        var stopTime = 0L
        var yielded = false
        val label get() = "${work.name}#$id"
    }

    private val waiting = mutableListOf<Ticket>()
    private val active = mutableSetOf<Ticket>()
    private var sequence = 0L
    private lateinit var latest: CpuTopology

    // 状態だけをロック内で更新する。コルーチンの再開と JNI の取消はロック外で行う。
    private fun schedule(): List<() -> Unit> = buildList {
        for (gpu in listOf(false, true)) {
            while (true) {
                val next = waiting.filter { it.gpu == gpu }
                    .minWithOrNull(compareBy<Ticket> { it.work.priority }.thenBy { it.id }) ?: break
                val running = active.filter { it.gpu == gpu }
                val free = if (gpu) 0 else latest.budget - running.sumOf { it.granted }
                val target = minOf(next.requested, latest.budget)
                val count = when {
                    gpu -> next.requested
                    next.work.resize == ResizeCost.Cheap -> minOf(target, free)
                    else -> target
                }
                if (if (gpu) running.isNotEmpty() else count <= 0 || count > free || running.any { it.requested == Int.MAX_VALUE }) {
                    if (!gpu) {
                        var needed = (if (next.work.resize == ResizeCost.Cheap) 1 else target) - free
                        needed -= running.filter { it.stoppedBy != null }.sumOf { it.granted }
                        for (victim in running.sortedByDescending { it.work.priority }) {
                            if (needed <= 0) break
                            if (victim.work.priority <= next.work.priority || victim.work.stop == StopMode.Finish || victim.stoppedBy != null) continue
                            victim.stoppedBy = next
                            victim.stopTime = System.nanoTime()
                            needed -= victim.granted
                            if (victim.work.stop == StopMode.Cancel) add { victim.job.cancel() }
                        }
                    }
                    break
                }
                waiting.remove(next)
                next.granted = count
                next.topology = latest
                active.add(next)
                val ready = next.ready
                add { ready.complete(Unit) }
            }
        }
    }

    suspend fun <T> borrow(topology: CpuTopology, gpu: Boolean, requested: Int, work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T): T = coroutineScope {
        require(requested >= 0)
        val job = coroutineContext.job
        var start = System.nanoTime()
        val ticket = synchronized(waiting) {
            latest = topology
            Ticket(++sequence, gpu, work, if (requested == 0) topology.budget else requested, job).also { waiting.add(it) }
        }
        try {
            while (true) {
                synchronized(waiting) { schedule() }.forEach { it() }
                ticket.ready.await()
                ensureActive()
                log("計算資源 ${ticket.label} 組=${work.priority} 停止=${work.stop} GPU=$gpu CPU段=${ticket.topology.tiers} 予算=${ticket.topology.budget} 貸出=${ticket.granted} 待ち=${(System.nanoTime()-start)/1_000_000}ms")
                val result = action(ticket.granted) {
                    synchronized(waiting) {
                        if (work.stop == StopMode.Boundary) {
                            ticket.yielded = ticket.stoppedBy != null || gpu && waiting.any { it.gpu && it.work.priority < work.priority }
                            ticket.yielded
                        } else waiting.any { it.gpu == gpu }
                    }
                }
                if (!ticket.yielded) return@coroutineScope result
                synchronized(waiting) {
                    release(ticket)
                    ticket.yielded = false
                    ticket.ready = CompletableDeferred()
                    waiting.add(ticket)
                    start = System.nanoTime()
                }
            }
            error("到達不能")
        } finally {
            synchronized(waiting) {
                waiting.remove(ticket)
                release(ticket)
                schedule()
            }.forEach { it() }
        }
    }

    private fun release(ticket: Ticket) {
        active.remove(ticket)
        ticket.stoppedBy?.let {
            log("計算資源 横取り ${it.label} → ${ticket.label} 返却まで=${(System.nanoTime()-ticket.stopTime)/1_000_000}ms")
            ticket.stoppedBy = null
        }
    }
}
