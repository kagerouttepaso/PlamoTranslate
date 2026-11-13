package app.plamotranslate.diagnostics

import android.app.ApplicationExitInfo
import android.content.Context
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashReports
import app.plamotranslate.pipeline.diagnostics.ExitSelection
import app.plamotranslate.pipeline.diagnostics.ProcessExit
import java.io.File

/**
 * OS の終わり方の記録の取り込み（外とつなぐ部品）。起動のたびに前回までの終わり方を読み、何を記録するかを中核の
 * [ExitSelection.plan] に決めさせ、OS が残した様子と、そのプロセスのログ（[ProcessLogFile]）と一緒に記録へ書く。
 * ネイティブのライブラリでの落ち方・応答なし・OS に止められた終わり方は、例外の受け口では受け止められないので、ここで知る。
 * 取り込んだ後、OS の記録で終わり方を見たプロセスのログのファイルを消す（[ProcessLogFile.cleanUp]）。
 *
 * 命令 [collect] はすぐ戻り、裏のスレッドで読む。結果は記録の一覧（[CrashJournal.entries]）で届く。
 */
class ExitIntake(
    private val context: Context,
    private val journal: CrashJournal,
    private val preferences: DiagnosticsPreferences,
    private val logDir: File,
    private val currentKey: String,
) {
    fun collect() {
        Thread({ runCatching { collectNow() }.onFailure { Logs.w("不具合の記録：終わり方を読めませんでした", it) } }, "ExitIntake")
            .apply { isDaemon = true }
            .start()
    }

    private fun collectNow() {
        // OS の記録と、その読み替えを組にして持ち回る（OS が残した様子を、後で同じ記録から読むため）
        val history: List<Pair<ApplicationExitInfo, ProcessExit>> = ProcessExits.history(context).map { it to it.toProcessExit() }
        val selection = ExitSelection.plan(history, { it.second }, preferences.exitsReadUntil)
        if (selection.record.isNotEmpty()) {
            val device = DeviceInfo.describe(context)
            for ((info, exit) in selection.record) {
                val log = ProcessLogFile.read(logDir, exit.processKey)
                journal.record(CrashReports.ofExit(exit, device, log, traceOf(info)))
            }
            Logs.i("不具合の記録：前回までの${selection.record.size}回の終わり方を記録した")
        }
        preferences.exitsReadUntil = selection.readUntil
        ProcessLogFile.cleanUp(logDir, currentKey, seenKeys = history.map { it.second.processKey }.toSet())
    }

    /** OS が残した様子（ネイティブの落ち方なら tombstone、応答なしならスレッドの様子）。無ければ null。 */
    private fun traceOf(info: ApplicationExitInfo): ByteArray? = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()
}
