package app.plamotranslate.diagnostics

import android.os.Process
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashReports

/**
 * 例外の受け口（外とつなぐ部品）。どのスレッドで受け止められなかった例外も、プロセスが終わる前に記録へ書き
 * （[CrashJournal.recordNow]）、それから元の受け口（Android の「停止しました」とプロセスの終了）へ渡す。
 * 落ちる直前なので、書くのはスタックトレースと端末の情報だけにする。ログは次の起動で [ExitIntake] が同じプロセスの記録へ足す。
 */
class CrashRecorder(private val journal: CrashJournal, private val device: String) {
    fun install() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val report = CrashReports.ofException(
                    System.currentTimeMillis(), Process.myPid(), ProcessMarks.current, thread.name, error, device,
                )
                journal.recordNow(report)
            }
            previous?.uncaughtException(thread, error)
        }
    }
}
