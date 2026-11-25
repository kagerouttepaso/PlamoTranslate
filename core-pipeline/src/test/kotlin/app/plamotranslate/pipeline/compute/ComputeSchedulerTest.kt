package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ComputeSchedulerTest {
    private val topology = CpuTopology(mapOf(2 to 10L, 5 to 20L, 7 to 20L))
    @Test fun 性能順に許された番号だけを数える() {
        assertEquals(listOf(2, 3), topology.candidates())
        assertEquals(listOf(1, 2, 3), CpuTopology(mapOf(2 to 2L, 5 to 3L, 7 to 4L)).candidates())
        assertEquals(listOf(2), CpuTopology(mapOf(3 to null, 8 to 1L)).candidates())
        assertEquals(listOf(2), CpuTopology(mapOf(3 to 0L, 8 to 0L)).candidates())
        assertNotEquals(topology.key, CpuTopology(mapOf(1 to 10L, 5 to 20L, 7 to 20L)).key)
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
                enqueue("取消", ComputeWork.FinalTranscript).cancelAndJoin()
                enqueue("確定", ComputeWork.FinalTranscript)
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
}
