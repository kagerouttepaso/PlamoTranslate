package app.plamotranslate.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import app.plamotranslate.pipeline.diagnostics.ExitCause
import app.plamotranslate.pipeline.diagnostics.ProcessExit

/**
 * OS が残す前回までの終わり方（[ApplicationExitInfo]）を読み、中核の [ProcessExit] に読み替える（外とつなぐ部品）。
 * 異常終了かどうかの判定は中核の `ExitJudge` が行う。
 */
object ProcessExits {
    /** このアプリの終わり方の記録（新しい順）。[pid] が0なら全部、そうでなければそのプロセスの分だけ。 */
    fun history(context: Context, pid: Int = 0, max: Int = 0): List<ApplicationExitInfo> = runCatching {
        context.getSystemService(ActivityManager::class.java).getHistoricalProcessExitReasons(context.packageName, pid, max)
    }.getOrDefault(emptyList())

    /** OS の理由の値を原因に読み替える。 */
    fun causeOf(reason: Int): ExitCause = when (reason) {
        ApplicationExitInfo.REASON_CRASH -> ExitCause.EXCEPTION
        ApplicationExitInfo.REASON_CRASH_NATIVE -> ExitCause.NATIVE_CRASH
        ApplicationExitInfo.REASON_ANR -> ExitCause.NOT_RESPONDING
        ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> ExitCause.INIT_FAILURE
        ApplicationExitInfo.REASON_EXIT_SELF -> ExitCause.EXIT_SELF
        ApplicationExitInfo.REASON_SIGNALED -> ExitCause.SIGNALED
        ApplicationExitInfo.REASON_LOW_MEMORY -> ExitCause.LOW_MEMORY
        ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> ExitCause.EXCESSIVE_RESOURCE
        ApplicationExitInfo.REASON_USER_REQUESTED, ApplicationExitInfo.REASON_USER_STOPPED -> ExitCause.USER_STOPPED
        else -> ExitCause.OTHER
    }

    /**
     * 終わったとき本人が使っていたか。前面の画面・フォアグラウンドサービス（画面翻訳）・本人が気づく処理の重要度なら、使っていた。
     */
    fun inUse(importance: Int): Boolean = importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_PERCEPTIBLE
}

/** 中核の [ProcessExit] に読み替える。 */
fun ApplicationExitInfo.toProcessExit(): ProcessExit = ProcessExit(
    time = timestamp,
    pid = pid,
    cause = ProcessExits.causeOf(reason),
    status = status,
    inUse = ProcessExits.inUse(importance),
    rawReason = reason,
    description = description.orEmpty(),
)
