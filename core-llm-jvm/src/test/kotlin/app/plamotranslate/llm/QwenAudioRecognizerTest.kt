package app.plamotranslate.llm

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class QwenAudioRecognizerTest {
    @Test fun 次の読込は前の解放を待ち待機中の取消で許可を失わない() = runBlocking {
        withTimeout(5_000) {
            val models = FakeModels()
            models.load().use { first ->
                val cancelled = async(start = CoroutineStart.UNDISPATCHED) { models.load() }
                val next = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) { models.load() }
                assertFalse(cancelled.isCompleted)
                assertFalse(next.isCompleted)
                cancelled.cancelAndJoin()
                first.close()
                next.await().close()
            }
            assertEquals(2, models.loads)
            assertFalse(models.live)
        }
    }

    @Test fun 読込失敗と引き渡し前の取消でも次を読み込める() = runBlocking {
        withTimeout(5_000) {
            val models = FakeModels()
            assertTrue(runCatching {
                QwenAudioRecognizer.loadExclusive<Unit> { error("読み込み失敗") }
            }.exceptionOrNull() is IllegalStateException)

            val cancelled = async {
                models.load { currentCoroutineContext().cancel() }
            }
            cancelled.join()
            assertTrue(cancelled.isCancelled)
            assertFalse(models.live)
            models.load().close()
            assertEquals(2, models.loads)
        }
    }

    private class FakeModels {
        var loads = 0
            private set
        var live = false
            private set

        suspend fun load(afterLoad: suspend () -> Unit = {}): QwenAudioRecognizer = QwenAudioRecognizer.loadExclusive {
            assertFalse(live)
            live = true
            loads++
            val session = QwenAudioSession(ProcessCompute, false, 1, 1, 1L, { error("読み直しは試験の対象外") }) {
                assertTrue(live)
                live = false
            }
            val recognizer = QwenAudioRecognizer(session)
            afterLoad()
            recognizer to Unit
        }.first
    }
}
