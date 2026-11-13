package app.plamotranslate.diagnostics

import android.app.ApplicationExitInfo
import android.content.Context
import android.util.Log
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashReports
import app.plamotranslate.pipeline.diagnostics.ExitSelection
import app.plamotranslate.pipeline.diagnostics.ProcessExit

/**
 * OS の終わり方の記録の取り込み（外とつなぐ部品）。起動のたびに前回までの終わり方を読み、何を記録するかを中核の
 * [ExitSelection.plan] に決めさせ、OS が残した様子とログの写しと一緒に記録へ書く。
 * ネイティブのライブラリでの落ち方・応答なし・OS に止められた終わり方は、例外の受け口では受け止められないので、ここで知る。
 *
 * 命令 [collect] はすぐ戻り、裏のスレッドで読む。結果は記録の一覧（[CrashJournal.entries]）で届く。
 */
class ExitIntake(
    private val context: Context,
    private val journal: CrashJournal,
    private val preferences: DiagnosticsPreferences,
) {
    fun collect() {
        Thread({ runCatching { collectNow() }.onFailure { Log.w(TAG, "不具合の記録：終わり方を読めませんでした", it) } }, "ExitIntake")
            .apply { isDaemon = true }
            .start()
    }

    private fun collectNow() {
        // OS の記録と、その読み替えを組にして持ち回る（OS が残した様子を、後で同じ記録から読むため）
        val history: List<Pair<ApplicationExitInfo, ProcessExit>> = ProcessExits.history(context).map { it to it.toProcessExit() }
        val selection = ExitSelection.plan(history, { it.second }, preferences.exitsReadUntil)
        if (selection.record.isNotEmpty()) {
            val device = DeviceInfo.describe(context)
            // ログの写しは取り込む時点の新しい行なので、一番新しい終わり方にだけ添える
            val log = AppLog.snapshot()
            for (item in selection.record) {
                val (info, exit) = item
                journal.record(CrashReports.ofExit(exit, device, log.takeIf { item == selection.withLog }, traceOf(info)))
            }
            Log.i(TAG, "不具合の記録：前回までの${selection.record.size}回の終わり方を記録した")
        }
        preferences.exitsReadUntil = selection.readUntil
    }

    /** OS が残した様子（ネイティブの落ち方なら tombstone、応答なしならスレッドの様子）。無ければ null。 */
    private fun traceOf(info: ApplicationExitInfo): ByteArray? = runCatching { info.traceInputStream?.use { it.readBytes() } }.getOrNull()

    private companion object {
        const val TAG = "PlamoTranslate"
    }
}
