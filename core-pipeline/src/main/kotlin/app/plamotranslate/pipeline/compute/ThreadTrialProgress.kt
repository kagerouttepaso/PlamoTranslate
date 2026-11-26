package app.plamotranslate.pipeline.compute

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ThreadTrialRow {
    data object Pending : ThreadTrialRow
    data object Measuring : ThreadTrialRow
    data object CutOff : ThreadTrialRow
    data class Measured(val nanos: Long) : ThreadTrialRow
}

data class ThreadTrialProgress(
    val component: String,
    val budget: Int,
    val rows: List<ThreadTrialRow>,
    val selected: Int? = null,
)

// 同時に始まった別の部品の試走は保持し、表示中の試走が終わってから表示する。
internal object ActiveThreadTrials {
    private val active = linkedMapOf<Any, ThreadTrialProgress>()
    private val current = MutableStateFlow<ThreadTrialProgress?>(null)
    val progress = current.asStateFlow()

    @Synchronized fun publish(id: Any, value: ThreadTrialProgress?) {
        if (value == null) active.remove(id) else active[id] = value
        current.value = active.values.firstOrNull()
    }

    fun visible(value: ThreadTrialProgress) = current.subscriptionCount.value > 0 && current.value === value
}
