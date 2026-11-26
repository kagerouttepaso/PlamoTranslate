package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ComputeSchedulerTest {
    private fun core(group: Int, speed: Long) = CpuTopology.Core(group, speed)
    private val topology = CpuTopology(mapOf(2 to core(0, 10), 5 to core(4, 20), 7 to core(4, 20)))

    @Test fun 許されたCPUだけを所属ごとに速い順に数える() {
        assertEquals(listOf(listOf(5, 7), listOf(2)), topology.tiers)
        assertEquals(listOf(2, 3), topology.candidates())
        val three = CpuTopology(mapOf(2 to core(0, 10), 5 to core(4, 20), 7 to core(7, 20)))
        assertEquals(listOf(listOf(5), listOf(7), listOf(2)), three.tiers)
        assertEquals(listOf(1, 2, 3), three.candidates())
        assertNotEquals(topology.key, three.key)
        assertNotEquals(topology.key, CpuTopology(mapOf(2 to core(0, 10), 5 to core(4, 20))).key)
    }

    @Test fun 同じ所属で性能値が揺れても段と保存鍵を保つ() {
        val changed = CpuTopology(mapOf(7 to core(4, 19), 5 to core(4, 21), 2 to core(0, 9)))
        assertEquals(topology.tiers, changed.tiers)
        assertEquals(topology.key, changed.key)
    }

    @Test fun 所属が一つか情報が欠けるときは全予算が唯一の候補になる() {
        for (first in listOf(null, core(1, 9))) {
            val cpu = CpuTopology(mapOf(3 to first, 8 to core(1, 10)))
            assertEquals(listOf(listOf(3, 8)), cpu.tiers)
            assertEquals(listOf(2), cpu.candidates())
        }
    }

    @Test fun 資源ごとの上限と優先順位と待ちの通知() = runTest {
        for (gpu in listOf(false, true)) {
            val scheduler = ComputeScheduler()
            val order = mutableListOf<String>()
            scheduler.borrow(topology, gpu, 2) { count, waiting ->
                assertEquals(2, count)
                fun enqueue(name: String, work: ComputeWork) = launch(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.borrow(topology, gpu, 2, work) { _, _ -> order.add(name) }
                }
                enqueue("先着", ComputeWork.Regular)
                enqueue("取消", app.plamotranslate.pipeline.speech.RecognitionPriority.FINAL.work).cancelAndJoin()
                enqueue("確定", app.plamotranslate.pipeline.speech.RecognitionPriority.FINAL.work)
                assertTrue(waiting())
                assertTrue(order.isEmpty())
            }
            testScheduler.runCurrent()
            assertEquals(listOf("確定", "先着"), order)
        }
    }

    @Test fun 全予算を借りた処理は予算が増えても単独で動く() = runTest {
        val scheduler = ComputeScheduler()
        val small = CpuTopology(mapOf(0 to null))
        var entered = false
        scheduler.borrow(small, false, Int.MAX_VALUE) { _, _ ->
            launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.borrow(topology, false, 1) { _, _ -> entered = true }
            }
            assertFalse(entered)
            scheduler.borrow(topology, true, 1) { _, _ -> assertFalse(entered) }
        }
        testScheduler.runCurrent()
        assertTrue(entered)
    }

    @Test fun 予算が減ったら返却を待つ() = runTest {
        val scheduler = ComputeScheduler()
        var granted = 0
        scheduler.borrow(topology, false, 2) { _, waiting ->
            scheduler.borrow(topology, false, 1) { _, _ ->
                launch(start = CoroutineStart.UNDISPATCHED) {
                    scheduler.borrow(CpuTopology(mapOf(0 to null)), false, 3) { n, _ -> granted = n }
                }
            }
            assertTrue(waiting())
            assertEquals(0, granted)
        }
        yield()
        assertEquals(1, granted)
    }

    @Test fun 高い組が取り消してよい依頼を止めてから借りる() = runTest {
        val scheduler = ComputeScheduler()
        val order = mutableListOf<String>()
        val low = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.borrow(topology, false, topology.budget, ComputeWork(ComputePriority.Speculative, StopMode.Cancel)) { _, _ ->
                try { awaitCancellation() } finally { order.add("返却") }
            }
        }
        scheduler.borrow(topology, false, 1) { _, _ -> order.add("貸出") }
        low.join()
        assertTrue(low.isCancelled)
        assertEquals(listOf("返却", "貸出"), order)
    }

    @Test fun 区切りで譲った依頼は後続より先に戻る() = runTest {
        val scheduler = ComputeScheduler()
        val order = mutableListOf<String>()
        scheduler.borrow(topology, false, topology.budget, ComputeWork.Translation) { _, boundary ->
            if (order.isEmpty()) {
                for ((name, priority) in listOf("後続" to ComputePriority.Normal, "確定" to ComputePriority.Critical)) {
                    launch(start = CoroutineStart.UNDISPATCHED) {
                        scheduler.borrow(topology, false, topology.budget, ComputeWork(priority)) { _, _ -> order.add(name) }
                    }
                }
                assertTrue(order.isEmpty())
                assertTrue(boundary())
                order.add("区切り")
            } else order.add("再開")
        }
        testScheduler.runCurrent()
        assertEquals(listOf("区切り", "確定", "再開", "後続"), order)
    }

    @Test fun 軽い処理は低い組が止められる分を取り返してから借りる() = runTest {
        val scheduler = ComputeScheduler()
        val partial = launch(start = CoroutineStart.UNDISPATCHED) {
            scheduler.borrow(topology, false, topology.budget - 1, ComputeWork(ComputePriority.Speculative, StopMode.Cancel)) { _, _ ->
                awaitCancellation()
            }
        }
        var granted = 0
        scheduler.borrow(topology, false, topology.budget, ComputeWork.Translation) { n, _ -> granted = n }
        assertTrue(partial.isCancelled)
        assertEquals(topology.budget, granted)
    }

    @Test fun 軽い処理は空きだけ借り重い処理は全数を待つ() = runTest {
        val scheduler = ComputeScheduler()
        val counts = mutableListOf<Int>()
        scheduler.borrow(topology, false, 1) { _, _ ->
            scheduler.borrow(topology, false, topology.budget, ComputeWork.Translation) { n, _ -> counts.add(n) }
            launch(start = CoroutineStart.UNDISPATCHED) {
                scheduler.borrow(topology, false, topology.budget) { n, _ -> counts.add(n) }
            }
            assertEquals(listOf(topology.budget - 1), counts)
        }
        testScheduler.runCurrent()
        assertEquals(listOf(topology.budget - 1, topology.budget), counts)
    }
}
