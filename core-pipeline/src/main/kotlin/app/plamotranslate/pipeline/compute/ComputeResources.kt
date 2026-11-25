package app.plamotranslate.pipeline.compute

/** 処理の間だけ資源を借りる。処理には貸出スレッド数と、同じ資源を待つ依頼の有無を渡す。 */
interface ComputeResources {
    suspend fun <T> borrow(
        gpu: Boolean,
        threads: Int,
        work: ComputeWork = ComputeWork.Regular,
        action: suspend (Int, () -> Boolean) -> T,
    ): T
}
