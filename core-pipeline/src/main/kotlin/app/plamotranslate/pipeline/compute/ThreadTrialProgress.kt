package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

sealed interface ThreadTrialRow {
    data object Pending : ThreadTrialRow
    data object Measuring : ThreadTrialRow
    data object CutOff : ThreadTrialRow
    data class Measured(val nanos: Long) : ThreadTrialRow
}

data class ThreadTrialProgress(
    val id: String,
    val purpose: ThreadBenchmark.Purpose,
    val rows: List<ThreadTrialRow>,
    val selected: Int? = null,
)

// 同時に始まった別の部品の試走は保持し、表示中の試走が終わってから表示する。
class ThreadTrialBoard {
    private val active = linkedMapOf<String, ThreadTrialProgress>()
    // 選択と直後の終了を、表示側が同じフレームで受けても順に読めるようにする。
    private val current = MutableSharedFlow<ThreadTrialProgress?>(replay = 1, extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST).apply { tryEmit(null) }
    val progress = current.asSharedFlow()

    @Synchronized fun publish(id: String, value: ThreadTrialProgress?) {
        if (value == null) active.remove(id) else active[id] = value
        val next = active.values.firstOrNull()
        if (current.replayCache.last() != next) current.tryEmit(next)
    }
}
