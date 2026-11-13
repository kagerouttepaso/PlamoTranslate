package app.plamotranslate.diagnostics

import android.content.Context
import android.util.Log
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashReports
import java.io.File
import java.io.OutputStream

/**
 * 不具合の記録の組み立て（docs/設計/アーキテクチャ.md「不具合の記録」）。記録は端末の中（`files/diagnostics/`）にだけ置き、
 * 本人が設定から書き出す。どこにも送らない。
 *
 * 部品は、プロセスの印（[ProcessMarks]）、記録の保存（[FileCrashJournal]。差し込み口は中核の [CrashJournal]）、
 * 例外の受け口（[CrashRecorder]）、OS の終わり方の取り込み（[ExitIntake]）、ログの写し（[AppLog]）に分かれ、ここでつなぐ。
 */
object Diagnostics {
    private const val TAG = "PlamoTranslate"
    private const val DIR = "diagnostics"

    @Volatile
    private var journal: CrashJournal? = null

    /** 記録の保存先。アプリ全体で1つ。 */
    fun journal(context: Context): CrashJournal = journal ?: synchronized(this) {
        journal ?: FileCrashJournal(File(context.applicationContext.filesDir, DIR)).also { journal = it }
    }

    /**
     * プロセスが始まるときに1度呼ぶ（`App.onCreate`）。プロセスの印を預け、例外の受け口を置き、前回までの終わり方を取り込む。
     * 調べるための仕組みがアプリを止めないよう、ここでの失敗はログに残すだけにする。
     */
    fun install(context: Context) {
        runCatching {
            val app = context.applicationContext
            ProcessMarks.begin(app)
            val journal = journal(app)
            CrashRecorder(journal, DeviceInfo.describe(app)).install()
            ExitIntake(app, journal, DiagnosticsPreferences(app)).collect()
        }.onFailure { Log.w(TAG, "不具合の記録を用意できませんでした", it) }
    }

    /** 記録を全部と、書き出した時点の端末の情報とログを、zip にして [out] へ書く。 */
    suspend fun export(context: Context, out: OutputStream) {
        val now = mapOf(
            CrashReports.NOW_DEVICE to DeviceInfo.describe(context).toByteArray(),
            CrashReports.NOW_LOG to AppLog.snapshot().toByteArray(),
        )
        journal(context).export(now, out)
    }
}
