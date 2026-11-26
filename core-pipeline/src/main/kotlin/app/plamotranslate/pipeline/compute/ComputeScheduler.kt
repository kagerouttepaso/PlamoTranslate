package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.job

enum class ComputePriority { Critical, Interactive, Normal, Speculative }
enum class StopMode { Finish, Cancel, Boundary }
enum class ResizeCost { Reload, Cheap }

data class ComputeWork(
    val priority: ComputePriority = ComputePriority.Normal,
    val stop: StopMode = StopMode.Finish,
    val resize: ResizeCost = ResizeCost.Reload,
    val name: String = priority.name,
    val benchmark: ThreadBenchmark? = null,
    val exclusiveCpu: Boolean = false,
) {
    companion object {
        val Regular = ComputeWork()
        val Translation = ComputeWork(stop = StopMode.Boundary, resize = ResizeCost.Cheap, name = "翻訳")
    }
}

/**
 * 1回の貸出（待っている依頼なら、頼んだ数と並んだ時刻）。時刻は [System.nanoTime] のミリ秒。
 * 終わっていなければ [end] は null。[preempted] は、優先度の高い依頼に止められて返したか。
 */
data class ComputeLease(
    val id: Long, val name: String, val priority: ComputePriority, val gpu: Boolean, val threads: Int,
    val start: Long, val end: Long? = null, val preempted: Boolean = false, val trial: Boolean = false,
)

/** 貸出の様子。詳細設定で選ぶと、会話の画面に図で出す。[recent] は直近 [ComputeScheduler.HISTORY_MILLIS] に終わった貸出。 */
data class ComputeActivity(val budget: Int = 0, val running: List<ComputeLease> = emptyList(),
    val waiting: List<ComputeLease> = emptyList(), val recent: List<ComputeLease> = emptyList())

class ComputeScheduler(private val log: (String) -> Unit = {}) {
    private class Ticket(val id: Long, val gpu: Boolean, val work: ComputeWork, val requested: Int, val job: Job) {
        var ready = CompletableDeferred<Unit>()
        var granted = 0
        lateinit var topology: CpuTopology
        var stoppedBy: Ticket? = null
        var stopTime = 0L
        var yielded = false
        var since = now()
        val exclusive get() = work.exclusiveCpu || requested == Int.MAX_VALUE
        val label get() = "${work.name}#$id"
    }

    private val waiting = mutableListOf<Ticket>()
    private val active = mutableSetOf<Ticket>()
    private var sequence = 0L
    private lateinit var latest: CpuTopology
    private val history = ArrayDeque<ComputeLease>()
    private val activityState = MutableStateFlow(ComputeActivity())
    val activity: StateFlow<ComputeActivity> = activityState.asStateFlow()

    private fun Ticket.lease(end: Long? = null, preempted: Boolean = false) =
        ComputeLease(id, work.name, work.priority, gpu, if (this in active) granted else requested, since, end, preempted, work.exclusiveCpu)

    // 図を出していない間（購読が無い間）は、貸出のたびに一覧を作り直さない。履歴だけは持つ。
    private fun publish() {
        if (activityState.subscriptionCount.value == 0) return
        val horizon = now() - HISTORY_MILLIS
        while (history.firstOrNull()?.let { it.end!! < horizon } == true) history.removeFirst()
        activityState.value = ComputeActivity(latest.budget, active.map { it.lease() }, waiting.map { it.lease() }, history.toList())
    }

    // 状態だけをロック内で更新する。コルーチンの再開と JNI の取消はロック外で行う。
    private fun schedule(): List<() -> Unit> = buildList {
        for (gpu in listOf(false, true)) {
            while (true) {
                val next = waiting.filter { it.gpu == gpu }
                    .minWithOrNull(compareBy<Ticket> { it.work.priority }.thenBy { it.id }) ?: break
                val running = active.filter { it.gpu == gpu }
                val free = if (gpu || running.any { it.exclusive }) 0 else latest.budget - running.sumOf { it.granted }
                val target = minOf(next.requested, latest.budget)
                // 低い組が止められる形で持っている CPU は、軽い依頼の空きにも数える（優先度の順を、本数の決め方でも守る）。
                val reclaimable = if (gpu) 0 else running
                    .filter { it.work.priority > next.work.priority && it.work.stop != StopMode.Finish }
                    .sumOf { it.granted }
                val count = when {
                    gpu -> next.requested
                    next.work.resize == ResizeCost.Cheap -> minOf(target, free + reclaimable)
                    else -> target
                }
                val blocked = if (gpu) running.isNotEmpty() else
                    count !in 1..free || next.exclusive && running.isNotEmpty()
                if (blocked) {
                    if (!gpu) {
                        val minimum = when {
                            next.exclusive -> latest.budget
                            next.work.resize == ResizeCost.Cheap -> maxOf(1, count)
                            else -> target
                        }
                        addAll(preempt(next, running, minimum - free))
                    } else if (next.work.priority < running.single().work.priority && running.single().work.stop == StopMode.Boundary) {
                        requestStop(running.single(), next)
                    }
                    break
                }
                waiting.remove(next)
                next.since = now()
                next.granted = count
                next.topology = latest
                active.add(next)
                val ready = next.ready
                add { ready.complete(Unit) }
            }
        }
        publish()
    }

    private fun requestStop(victim: Ticket, next: Ticket) {
        if (victim.stoppedBy != null) return
        victim.stoppedBy = next
        victim.stopTime = System.nanoTime()
    }

    private fun preempt(next: Ticket, running: List<Ticket>, deficit: Int): List<() -> Unit> = buildList {
        var needed = deficit - running.filter { it.stoppedBy != null }.sumOf { it.granted }
        for (victim in running.sortedByDescending { it.work.priority }) {
            if (needed <= 0) break
            if (victim.work.priority <= next.work.priority || victim.work.stop == StopMode.Finish || victim.stoppedBy != null) continue
            requestStop(victim, next)
            needed -= victim.granted
            if (victim.work.stop == StopMode.Cancel) add { victim.job.cancel() }
        }
    }

    suspend fun <T> borrow(topology: CpuTopology, gpu: Boolean, requested: Int, work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T): T = coroutineScope {
        require(requested > 0)
        val job = coroutineContext.job
        var start = System.nanoTime()
        val ticket = synchronized(waiting) {
            latest = topology
            Ticket(++sequence, gpu, work, requested, job).also { waiting.add(it) }
        }
        try {
            while (true) {
                synchronized(waiting) { schedule() }.forEach { it() }
                ticket.ready.await()
                ensureActive()
                log("計算資源 ${ticket.label} 組=${work.priority} 停止=${work.stop} GPU=$gpu CPU単独=${!gpu && ticket.exclusive} CPU段=${ticket.topology.tiers} 予算=${ticket.topology.budget} 貸出=${ticket.granted} 待ち=${(System.nanoTime() - start) / 1_000_000}ms")
                val result = action(ticket.granted) {
                    synchronized(waiting) {
                        if (work.stop == StopMode.Boundary) {
                            if (ticket.stoppedBy !in waiting && waiting.none { it.gpu == gpu && it.work.priority < work.priority }) {
                                ticket.stoppedBy = null
                            }
                            ticket.yielded = ticket.stoppedBy != null
                            ticket.yielded
                        } else waiting.any { it.gpu == gpu }
                    }
                }
                if (!ticket.yielded) return@coroutineScope result
                synchronized(waiting) {
                    release(ticket)
                    ticket.yielded = false
                    ticket.ready = CompletableDeferred()
                    ticket.since = now()
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
        if (ticket in active) {
            history.addLast(ticket.lease(now(), ticket.stoppedBy != null))
            if (history.size > HISTORY_LIMIT) history.removeFirst()
        }
        active.remove(ticket)
        ticket.stoppedBy?.let {
            log("計算資源 横取り ${it.label} → ${ticket.label} 返却まで=${(System.nanoTime() - ticket.stopTime) / 1_000_000}ms")
            ticket.stoppedBy = null
        }
    }

    companion object {
        const val HISTORY_MILLIS = 15_000L
        private const val HISTORY_LIMIT = 500
        private fun now() = System.nanoTime() / 1_000_000
    }
}
