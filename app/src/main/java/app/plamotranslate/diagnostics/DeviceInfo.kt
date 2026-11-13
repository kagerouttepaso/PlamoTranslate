package app.plamotranslate.diagnostics

import android.content.Context
import android.os.Build

/** 記録に添える、アプリの版と端末の情報。 */
object DeviceInfo {
    fun describe(context: Context): String {
        val pkg = runCatching { context.packageManager.getPackageInfo(context.packageName, 0) }.getOrNull()
        return buildString {
            append("アプリ：").append(pkg?.versionName).append("（versionCode ").append(pkg?.longVersionCode).append("）\n")
            append("端末：").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append("（").append(Build.HARDWARE).append("）\n")
            append("Android：").append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n")
        }
    }
}
