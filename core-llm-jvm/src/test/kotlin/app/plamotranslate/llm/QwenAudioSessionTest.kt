package app.plamotranslate.llm

import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeWork
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class QwenAudioSessionTest {
    @Test fun 予算が戻れば選んだ数に戻り読込失敗は次の依頼で回復する() = runBlocking {
        var budget = 2
        val compute = object : ComputeResources {
            override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
                action: suspend (Int, () -> Boolean) -> T): T {
                assertEquals(4, threads)
                return action(minOf(threads, budget)) { false }
            }
        }
        val freed = mutableListOf<Long>()
        val loads = mutableListOf<Int>()
        var fail = false
        var live = true
        val session = QwenAudioSession(compute, false, 4, 2, 1L, { count ->
            assertFalse(live)
            loads.add(count)
            check(!fail) { "読み込み失敗" }
            live = true
            loads.size.toLong() + 1
        }, { handle ->
            assertTrue(live)
            live = false
            freed.add(handle)
        })
        suspend fun use() = session.use(ComputeWork.Regular) { it }
        assertEquals(1L, use())
        budget = 4
        fail = true
        assertTrue(runCatching { use() }.exceptionOrNull() is IllegalStateException)
        fail = false
        // 失敗前と同じ貸出数に戻っても、解放済みのモデルを使わない。
        budget = 2
        // 失敗した読み込みでは解放すべきハンドルは増えない。
        assertEquals(listOf(1L), freed)
        assertEquals(3L, use())
        assertEquals(3L, use())
        budget = 4
        assertEquals(4L, use())
        session.close()
        assertEquals(listOf(4, 2, 4), loads)
        assertEquals(listOf(1L, 3L, 4L), freed)
    }
}
