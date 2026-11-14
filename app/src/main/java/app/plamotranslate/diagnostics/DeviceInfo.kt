package app.plamotranslate.diagnostics

import android.content.Context
import android.app.ActivityManager
import android.os.Build

/** 記録に添える、アプリの版と端末の情報。 */
object DeviceInfo {
    fun describe(context: Context): String {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            append("アプリ：").append(pkg?.versionName).append("（versionCode ").append(pkg?.longVersionCode).append("）\n")
            append("端末：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("（").append(Build.HARDWARE).append("）\n")
            append("Android：").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
            append("メモリ不足終了の報告対応：").append(ActivityManager.isLowMemoryKillReportSupported()).append('\n')
            // 書き出し時の値であり、過去の終了直前のメモリ量とは区別する。
            val memory = ActivityManager.MemoryInfo()
            context.getSystemService(ActivityManager::class.java).getMemoryInfo(memory)
            append("記録時のメモリ（bytes）：total=").append(memory.totalMem)
                .append(" available=").append(memory.availMem).append(" lowMemory=").append(memory.lowMemory).append('\n')
        }
    }
}
