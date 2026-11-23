package app.plamotranslate

import app.plamotranslate.pipeline.diagnostics.InferenceRole
import android.content.Context
import android.os.Build
import app.plamotranslate.diagnostics.ProcessExits
import app.plamotranslate.diagnostics.toProcessExit
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.diagnostics.ExitJudge
import app.plamotranslate.pipeline.diagnostics.ExitTexts
import app.plamotranslate.pipeline.diagnostics.GpuStartupPolicy
import app.plamotranslate.pipeline.diagnostics.GpuStartupPolicy.Decision
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GPU（OpenCL）での読み込み中にアプリが終わったら、次の起動からCPUで動かす（2026-11-09）。
 *
 * llama.cppのOpenCLの部分は、カーネルの組み立てに失敗すると `exit(1)` でプロセスごと終わる
 * （`ggml-opencl.cpp` の `build_program_from_source_ex` など）。戻り値で失敗が返らないので、
 * [app.plamotranslate.llm.TranslationEngine.load] のCPUでのやり直しが働かず、Androidがアプリを開き直すたびに同じ所で終わる。
 *
 * そこで、GPUで読み込む前に「読み込み中」のマーカー（プロセスID）を端末に書き、読み込みと初回の推論が済んだら消す。
 * 次の起動でマーカーが残っていれば、前回はGPUの読み込み中に終わっている。Androidが記録した前回の終了理由
 * （[ProcessExits]）が異常終了（中核の [ExitJudge.abnormal]）ならすぐ、そうでなくても2回続けばCPUに切り替える。
 * 1回目の強制停止やメモリ不足で止められただけでGPUを外さないよう、異常終了でないものは2回まで待つ。
 * CPUへの退避は更新後も維持し、設定の画面から明示的にGPUを試し直す。
 */
internal object GpuGuard {
    /**
     * 読み込みの前に呼ぶ。GPUを使ってよいか。前回GPUの読み込み中に終わっていれば、ここで決めて記録する。
     * 役割のGPU準備を直列化した区間で、今回のマーカーを書く前に呼ぶ。
     */
    fun allowGpu(context: Context, role: InferenceRole): Boolean {
        val preferences = GpuPreferences(context, role)
        val build = buildKey(context)
        val pendingPid = preferences.gpuLoadingPid
        val exit = if (pendingPid != 0) ProcessExits.history(context, pendingPid, 1).firstOrNull()?.toProcessExit() else null
        val exitLabel = exit?.let(ExitTexts::label) ?: "終了理由の記録なし"
        val abnormal = exit?.let(ExitJudge::abnormal) ?: false
        val intended = exit?.let(ExitJudge::intended) ?: false
        return when (val decision = GpuStartupPolicy.decide(preferences.gpuDisabled != null, pendingPid != 0, preferences.gpuInterruptions, abnormal, intended, exitLabel)) {
            Decision.KeepCpu -> false
            Decision.Normal -> {
                // 意図して起動し直したときは、読み込み中のマーカーを片付ける
                if (pendingPid != 0) preferences.gpuLoadingPid = 0
                true
            }
            is Decision.Retry -> {
                Logs.w("GPU: 前回はGPUでの読み込み中に止まりました（$exitLabel）。もう一度GPUで試します")
                preferences.gpuInterruptions = decision.interruptions
                preferences.gpuLoadingPid = 0
                true
            }
            is Decision.Disable -> {
                Logs.w("GPU: 前回はGPUでの読み込み中に止まりました（${decision.reason}）。CPUで動かします")
                preferences.gpuDisabled = GpuDisabled(decision.reason, build, System.currentTimeMillis())
                preferences.gpuLoadingPid = 0
                preferences.gpuInterruptions = 0
                false
            }
        }
    }

    /** GPUで読み込む直前に呼ぶ。プロセスごと終わっても残るよう、すぐに書く。 */
    fun beginGpuLoad(context: Context, role: InferenceRole) {
        GpuPreferences(context, role).gpuLoadingPid = android.os.Process.myPid()
    }

    /** GPUでの読み込みと初回の推論が終わったら呼ぶ（GPUで動いた、CPUでやり直した、失敗が返った、のどれでも）。 */
    fun endGpuLoad(context: Context, role: InferenceRole, failure: String? = null) {
        val preferences = GpuPreferences(context, role)
        if (failure != null) preferences.gpuDisabled = GpuDisabled(failure, buildKey(context), System.currentTimeMillis())
        preferences.gpuLoadingPid = 0
        preferences.gpuInterruptions = 0
    }

    /** 設定の画面の「GPUをもう一度試す」。次の起動でGPUを使う。 */
    fun retry(context: Context, role: InferenceRole) {
        val preferences = GpuPreferences(context, role)
        preferences.gpuDisabled = null
        preferences.gpuLoadingPid = 0
        preferences.gpuInterruptions = 0
    }

    /** GPUを外したときの説明（設定の画面に出す）。外していなければ null。 */
    fun disabledNote(context: Context, role: InferenceRole): String? = GpuPreferences(context, role).gpuDisabled?.let {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date(it.at))
        "${it.reason}、$at"
    }

    /** CPUへ退避した時点の版。診断用に残し、再試行の条件には使わない。 */
    private fun buildKey(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "$version/${Build.FINGERPRINT}"
    }
}

/** GPUを外した記録。[build] はそのときのアプリの版と端末のビルド（[GpuGuard]）。 */
data class GpuDisabled(val reason: String, val build: String, val at: Long)
