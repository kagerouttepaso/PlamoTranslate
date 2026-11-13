package app.plamotranslate

import android.content.Context
import android.os.Build
import android.util.Log
import app.plamotranslate.diagnostics.ProcessExits
import app.plamotranslate.diagnostics.toProcessExit
import app.plamotranslate.pipeline.diagnostics.ExitJudge
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GPU（OpenCL）での読み込み中にアプリが終わったら、次の起動からCPUで動かす（2026-11-09）。
 *
 * 同僚のROG Phone（Snapdragon 8 Gen 3、Adreno 750）で、翻訳モデルの読み込みのたびにアプリが再起動を繰り返した。
 * llama.cppのOpenCLの部分は、カーネルの組み立てに失敗すると `exit(1)` でプロセスごと終わる
 * （`ggml-opencl.cpp` の `build_program_from_source_ex` など）。戻り値で失敗が返らないので、
 * [app.plamotranslate.llm.TranslationEngine.load] のCPUでのやり直しが働かず、Androidがアプリを開き直すたびに同じ所で終わる。
 *
 * そこで、GPUで読み込む前に「読み込み中」の印（プロセスID）を端末に書き、読み込みと初回の推論が済んだら消す。
 * 次の起動で印が残っていれば、前回はGPUの読み込み中に終わっている。Androidが記録した前回の終わり方
 * （[ProcessExits]）が異常終了（中核の [ExitJudge.abnormal]）ならすぐ、そうでなくても2回続けばCPUに切り替える。
 * 1回目の強制停止やメモリ不足で止められただけでGPUを外さないよう、異常終了でないものは2回まで待つ。
 * アプリの版か端末のビルド（ドライバーを含む）が変わったら、もう一度GPUを試す。設定の画面からも試し直せる。
 */
internal object GpuGuard {
    private const val TAG = "PlamoTranslate"

    /** 異常終了でない中断（強制停止・メモリ不足など）が何回続いたらCPUに切り替えるか。 */
    const val MAX_INTERRUPTIONS = 2

    sealed interface Decision {
        /** 前回はGPUの読み込み中ではなかった。 */
        data object Normal : Decision

        /** 前回はGPUの読み込み中に止められたが、異常終了とは言えない。もう一度GPUで試す。 */
        data class Retry(val interruptions: Int) : Decision

        data class Disable(val reason: String) : Decision
    }

    /**
     * 前回の終わり方から決める。[abnormal] は印を書いたプロセスが異常終了したか（記録が見つからなければ false）。
     * [interruptions] はこれまでに続いた、異常終了でない中断の回数。
     */
    fun decide(pending: Boolean, interruptions: Int, abnormal: Boolean, exitLabel: String): Decision = when {
        !pending -> Decision.Normal
        abnormal -> Decision.Disable(exitLabel)
        interruptions + 1 >= MAX_INTERRUPTIONS -> Decision.Disable("GPUでの読み込みが${MAX_INTERRUPTIONS}回続けて終わりませんでした")
        else -> Decision.Retry(interruptions + 1)
    }

    /**
     * 読み込みの前に呼ぶ。GPUを使ってよいか。前回GPUの読み込み中に終わっていれば、ここで決めて記録する。
     * GPUを使わない設定・端末でも呼ぶ（前回の印をこの起動で片付けるため）。
     */
    fun allowGpu(context: Context): Boolean {
        val preferences = TranslationPreferences(context)
        val build = buildKey(context)
        preferences.gpuDisabled?.let { disabled ->
            if (disabled.build == build) return false
            // アプリの版か端末のビルドが変わった。直っているかもしれないので、もう一度GPUを試す
            Log.i(TAG, "GPU: 版が変わったので、もう一度GPUを試します（${disabled.reason}）")
            preferences.gpuDisabled = null
        }
        val pendingPid = preferences.gpuLoadingPid
        val exit = if (pendingPid != 0) ProcessExits.history(context, pendingPid, 1).firstOrNull()?.toProcessExit() else null
        val exitLabel = exit?.let(ExitJudge::label) ?: "終わり方の記録なし"
        val abnormal = exit?.let(ExitJudge::abnormal) ?: false
        return when (val decision = decide(pendingPid != 0, preferences.gpuInterruptions, abnormal, exitLabel)) {
            Decision.Normal -> true
            is Decision.Retry -> {
                Log.w(TAG, "GPU: 前回はGPUでの読み込み中に止まりました（$exitLabel）。もう一度GPUで試します")
                preferences.gpuInterruptions = decision.interruptions
                preferences.gpuLoadingPid = 0
                true
            }
            is Decision.Disable -> {
                Log.w(TAG, "GPU: 前回はGPUでの読み込み中に止まりました（${decision.reason}）。CPUで動かします")
                preferences.gpuDisabled = GpuDisabled(decision.reason, build, System.currentTimeMillis())
                preferences.gpuLoadingPid = 0
                preferences.gpuInterruptions = 0
                false
            }
        }
    }

    /** GPUで読み込む直前に呼ぶ。プロセスごと終わっても残るよう、すぐに書く。 */
    fun beginGpuLoad(context: Context) {
        TranslationPreferences(context).gpuLoadingPid = android.os.Process.myPid()
    }

    /** GPUでの読み込みと初回の推論が終わったら呼ぶ（GPUで動いた、CPUでやり直した、失敗が返った、のどれでも）。 */
    fun endGpuLoad(context: Context) {
        val preferences = TranslationPreferences(context)
        preferences.gpuLoadingPid = 0
        preferences.gpuInterruptions = 0
    }

    /** 自分でアプリを起動し直す前に呼ぶ。読み込み中に起動し直しても、GPUの失敗とみなさない。 */
    fun forgetPending(context: Context) {
        TranslationPreferences(context).gpuLoadingPid = 0
    }

    /** 設定の画面の「GPUをもう一度試す」。次の起動でGPUを使う。 */
    fun retry(context: Context) {
        val preferences = TranslationPreferences(context)
        preferences.gpuDisabled = null
        preferences.gpuLoadingPid = 0
        preferences.gpuInterruptions = 0
    }

    /** GPUを外したときの説明（設定の画面に出す）。外していなければ null。 */
    fun disabledNote(context: Context): String? = TranslationPreferences(context).gpuDisabled?.let {
        val at = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.JAPAN).format(Date(it.at))
        "${it.reason}、$at"
    }

    /** アプリの版と端末のビルド（GPUのドライバーを含む）。どちらかが変わったら、外したGPUをもう一度試す。 */
    private fun buildKey(context: Context): String {
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).longVersionCode }.getOrDefault(0L)
        return "$version/${Build.FINGERPRINT}"
    }
}

/** GPUを外した記録。[build] はそのときのアプリの版と端末のビルド（[GpuGuard]）。 */
data class GpuDisabled(val reason: String, val build: String, val at: Long)
