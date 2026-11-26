package app.plamotranslate.pipeline.compute

/** 初回の計測と、選んだ数での貸出を扱う。区切りで譲る処理は、返却後に同じ依頼の続きから再開する。 */
interface ComputeResources {

    /**
     * [requested] が0なら、[benchmark] で測って選ぶ。[capacity] は部品が使える数の上限。
     * [reserved] は、この部品と同時に走る別の部品が借りる CPU の数で、選ぶ数を予算からその分を引いた数（最低1）までにする。
     * 同時に走る部品が予算を取り合うと、優先度の高い方が低い方を毎回止め、低い方が終わらなくなるためである。
     */
    suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean = false, capacity: Int = Int.MAX_VALUE,
        reserved: Int = 0): Int

    suspend fun <T> borrow(
        gpu: Boolean,
        threads: Int = 0,
        work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T,
    ): T
}
