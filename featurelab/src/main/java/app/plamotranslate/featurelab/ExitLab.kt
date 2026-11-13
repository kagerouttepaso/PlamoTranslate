package app.plamotranslate.featurelab

import android.app.ActivityManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.Process
import java.util.UUID
import kotlin.system.exitProcess

/**
 * プロセスの終わり方の記録を確かめる（docs/資料/端末の能力.md「プロセスの終わり方」）。不具合の記録の設計の前に、
 * 次を1つずつ確かめる。
 *
 * - `setProcessStateSummary` で預けた印が、次の起動で `ApplicationExitInfo.getProcessStateSummary` から戻るか。
 * - 例外・`killProcess`・`exit(1)`・`exit(0)` で、OS が何（理由・状態・重要度・時刻）を残すか。
 * - 例外の受け口が動いた時刻と、OS の記録の時刻の差。
 * - 前のプロセス（同じ UID）が出したログの行を、`logcat -d` で読めるか。
 *
 * PC から `adb shell am start -n app.plamotranslate.featurelab/.MainActivity --es exit <exception|kill|exit1|exit0|none>` で動かし、
 * `adb logcat -s FeatureLab` で読む（ログを開けておく。docs/資料/端末の能力.md）。
 */
object ExitLab {
    private const val PREFS = "exit_lab"

    fun run(context: Context, mode: String?) {
        val am = context.getSystemService(ActivityManager::class.java)
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val handlerTime = prefs.getLong("handler_time", 0L)
        val handlerPid = prefs.getInt("handler_pid", 0)
        if (handlerTime != 0L) Lab.log("終わり方: 前回の受け口 pid=$handlerPid 時刻=$handlerTime")
        for (e in am.getHistoricalProcessExitReasons(context.packageName, 0, 6)) {
            Lab.log(
                "終わり方: pid=${e.pid} 時刻=${e.timestamp} 受け口との差=${if (e.pid == handlerPid) e.timestamp - handlerTime else "-"}ms " +
                    "理由=${e.reason} 状態=${e.status} 重要度=${e.importance} 印=${e.processStateSummary?.decodeToString()} 説明=${e.description}",
            )
        }
        val previous = previousLogLines()
        Lab.log("終わり方: 前のプロセスのログの行=$previous")

        val id = UUID.randomUUID().toString().take(8)
        am.setProcessStateSummary("lab:$id".toByteArray())
        Lab.log("終わり方: 印=lab:$id pid=${Process.myPid()} 終え方=$mode")
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            prefs.edit().putLong("handler_time", System.currentTimeMillis()).putInt("handler_pid", Process.myPid()).commit()
            previousHandler?.uncaughtException(t, e)
        }
        val main = Handler(Looper.getMainLooper())
        when (mode) {
            "exception" -> main.postDelayed({ throw IllegalStateException("featurelab の例外") }, 500)
            "kill" -> main.postDelayed({
                am.setProcessStateSummary("lab:$id:intended".toByteArray())
                Process.killProcess(Process.myPid())
            }, 500)
            "exit1" -> main.postDelayed({ exitProcess(1) }, 500)
            "exit0" -> main.postDelayed({ exitProcess(0) }, 500)
        }
    }

    /** 自分と違う pid の、FeatureLab の行の数。前のプロセスの行を読めるか。 */
    private fun previousLogLines(): Int = runCatching {
        val p = ProcessBuilder("logcat", "-d", "-b", "main,system,crash", "-v", "threadtime").redirectErrorStream(true).start()
        val me = Process.myPid().toString()
        p.inputStream.bufferedReader().useLines { lines ->
            lines.count { line ->
                val cols = line.trim().split(Regex("\\s+"))
                cols.size > 5 && cols[2] != me && line.contains(Lab.TAG)
            }
        }.also { p.destroy() }
    }.getOrDefault(-1)
}
