package app.plamotranslate.diagnostics

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Process
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashReports
import app.plamotranslate.pipeline.diagnostics.ProcessExit
import java.io.File
import java.io.OutputStream

/**
 * ログと不具合の記録の組み立て（docs/設計/アーキテクチャ.md「ログ」「不具合の記録」）。ログと記録は端末の中にだけ置き、
 * 本人が設定から書き出す。どこにも送らない。
 *
 * 部品は、プロセスの印（[ProcessMarks]）、ログの書き先（[LogcatSink]・[ProcessLogFile]。出口は core-log の [Logs]）、
 * 記録の保存（[FileCrashJournal]。差し込み口は中核の [CrashJournal]）、例外の受け口（[CrashRecorder]）、
 * OS の終わり方の取り込み（[ExitIntake]）に分かれ、ここでつなぐ。
 */
object Diagnostics {
    private const val RECORDS_DIR = "diagnostics"
    private const val LOGS_DIR = "logs"

    @Volatile
    private var journal: CrashJournal? = null

    @Volatile
    private var processLog: ProcessLogFile? = null

    /** 記録の保存先。アプリ全体で1つ。 */
    fun journal(context: Context): CrashJournal = journal ?: synchronized(this) {
        journal ?: FileCrashJournal(File(context.applicationContext.filesDir, RECORDS_DIR)).also { journal = it }
    }

    /**
     * プロセスが始まるときに1度呼ぶ（`App.onCreate`）。プロセスの印を預け、ログの書き先を差し、例外の受け口を置き、
     * 前回までの終わり方を取り込む。調べるための仕組みがアプリを止めないよう、ここでの失敗はアプリを止めない。
     */
    fun install(context: Context) {
        val app = context.applicationContext
        // 利用者の文はデバッグ版だけログに書く（Logs.text）
        val debuggable = (app.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        // まず logcat を差す。印を預けるときのログも残すため。ファイルは、プロセスの鍵が決まってから足す
        Logs.install(listOf(LogcatSink), userText = debuggable)
        ProcessMarks.begin(app)
        val key = ProcessExit.keyOf(ProcessMarks.current, Process.myPid())
        val logDir = File(app.filesDir, LOGS_DIR)
        runCatching {
            val file = ProcessLogFile(logDir, key).also { processLog = it }
            Logs.install(listOf(LogcatSink, file), userText = debuggable)
        }.onFailure { Logs.w("ログのファイルを用意できませんでした", it) }
        // 記録の部品の失敗では、ログの書き先を外さない
        runCatching {
            val journal = journal(app)
            CrashRecorder(journal, DeviceInfo.describe(app)).install()
            ExitIntake(app, journal, DiagnosticsPreferences(app), logDir, key).collect()
        }.onFailure { Logs.w("不具合の記録を用意できませんでした", it) }
    }

    /** 記録を全部と、書き出した時点の端末の情報と今のプロセスのログを、zip にして [out] へ書く。 */
    suspend fun export(context: Context, out: OutputStream) {
        val now = mapOf(
            CrashReports.NOW_DEVICE to DeviceInfo.describe(context).toByteArray(),
            CrashReports.NOW_LOG to (processLog?.read() ?: ByteArray(0)),
        )
        journal(context).export(now, out)
    }
}
