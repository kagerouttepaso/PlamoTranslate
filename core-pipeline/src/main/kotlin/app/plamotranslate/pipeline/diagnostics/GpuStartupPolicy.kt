package app.plamotranslate.pipeline.diagnostics

/**
 * GPUの起動準備の結果から、次の起動の計算先を決める。
 * 保存済みのCPU退避は明示的な再試行だけで解除する。アプリやOSの版は判断の入力にしない。
 */
object GpuStartupPolicy {
    const val MAX_INTERRUPTIONS = 2

    sealed interface Decision {
        data object KeepCpu : Decision
        data object Normal : Decision
        data class Retry(val interruptions: Int) : Decision
        data class Disable(val reason: String) : Decision
    }

    fun decide(
        cpuFallbackSaved: Boolean,
        pending: Boolean,
        interruptions: Int,
        abnormal: Boolean,
        intended: Boolean,
        exitLabel: String,
    ): Decision = when {
        cpuFallbackSaved -> Decision.KeepCpu
        !pending || intended -> Decision.Normal
        abnormal -> Decision.Disable(exitLabel)
        interruptions + 1 >= MAX_INTERRUPTIONS -> Decision.Disable("GPUでの読み込みが${MAX_INTERRUPTIONS}回続けて終わりませんでした")
        else -> Decision.Retry(interruptions + 1)
    }
}
