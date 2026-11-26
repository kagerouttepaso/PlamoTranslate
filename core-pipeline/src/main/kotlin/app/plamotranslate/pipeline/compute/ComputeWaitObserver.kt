package app.plamotranslate.pipeline.compute

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** 貸出ログと同じ待機区間を、呼び出し元の診断にも渡す。時刻は [System.nanoTime]。 */
class ComputeWaitObserver(val onGranted: (queuedAt: Long, grantedAt: Long) -> Unit) :
    AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<ComputeWaitObserver>
}
