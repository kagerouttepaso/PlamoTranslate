package app.plamotranslate.diagnostics

import android.util.Log
import app.plamotranslate.log.LogLevel
import app.plamotranslate.log.LogSink

/**
 * logcat への書き先（docs/設計/アーキテクチャ.md「ログ」）。アプリの中で `android.util.Log` を呼ぶのはここだけにする
 * （`tools/check-design.ps1` が見る）。開発中に `adb logcat -s PlamoTranslate` で読む。
 */
object LogcatSink : LogSink {
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message, error)
            LogLevel.INFO -> Log.i(tag, message, error)
            LogLevel.WARN -> Log.w(tag, message, error)
            LogLevel.ERROR -> Log.e(tag, message, error)
        }
    }
}
