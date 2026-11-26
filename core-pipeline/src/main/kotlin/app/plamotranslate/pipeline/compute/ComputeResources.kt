package app.plamotranslate.pipeline.compute

/** 初回の計測と、選んだ数での貸出を扱う。区切りで譲る処理は、返却後に同じ依頼の続きから再開する。 */
interface ComputeResources {
    val trialProgress: kotlinx.coroutines.flow.StateFlow<ThreadTrialProgress?> get() = ActiveThreadTrials.progress

    suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean = false, capacity: Int = Int.MAX_VALUE): Int

    suspend fun <T> borrow(
        gpu: Boolean,
        threads: Int = 0,
        work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T,
    ): T
}
