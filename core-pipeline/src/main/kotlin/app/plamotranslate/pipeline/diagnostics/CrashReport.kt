package app.plamotranslate.pipeline.diagnostics

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 不具合の記録に書く1件の中身。[process] は同じプロセスの記録を見分ける鍵（[ProcessExit.processKey]）で、
 * 保存の側が同じ鍵の記録にまとめる（[CrashJournals.target]）。[files] は記録に入れるファイルの名前と中身。
 */
class CrashReport(
    val time: Long,
    val process: String,
    val title: String,
    val files: Map<String, ByteArray>,
)

/** 記録の中身の組み立て。どこから知ったか（例外の受け口・OS の終わり方の記録）ごとに、入れるファイルを決める。 */
object CrashReports {
    const val REPORT = "report.txt"
    const val EXIT = "exit.txt"
    const val LOG = "log.txt"
    const val TRACE = "trace.txt"
    const val TOMBSTONE = "tombstone.pb"

    /** 書き出したときに添える、その時点の端末の情報とログ。 */
    const val NOW_DEVICE = "device.txt"
    const val NOW_LOG = "log.txt"

    private val TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())

    fun timeText(time: Long): String = TIME.format(Instant.ofEpochMilli(time))

    /**
     * 例外の受け口で、落ちる直前に書く記録。プロセスが終わる前に書き切るため、ログの写しは入れない
     * （次の起動で OS の終わり方の記録を取り込むときに、同じプロセスの記録へ足す）。
     */
    fun ofException(time: Long, pid: Int, mark: ProcessMark?, thread: String, error: Throwable, device: String): CrashReport {
        val exit = ProcessExit(time, pid, ExitCause.EXCEPTION, mark = mark)
        val body = buildString {
            append("種類：").append(ExitTexts.label(exit)).append('\n')
            append("時刻：").append(timeText(time)).append('\n')
            append("スレッド：").append(thread).append('\n')
            append(device).append('\n')
            append(error.stackTraceToString())
        }
        return CrashReport(time, exit.processKey, "例外で落ちた（${error.javaClass.simpleName}）", mapOf(REPORT to body.toByteArray()))
    }

    /**
     * OS の終わり方の記録から作る記録。[log] は取り込んだ時点の端末のログ、[trace] は OS が残した様子
     * （ネイティブの落ち方なら tombstone の protobuf、応答なしならスレッドの様子の文字）。
     */
    fun ofExit(exit: ProcessExit, device: String, log: String?, trace: ByteArray?): CrashReport {
        val label = ExitTexts.label(exit)
        val body = buildString {
            append("種類：").append(label).append('\n')
            append("時刻：").append(timeText(exit.time)).append('\n')
            append("重要度：").append(exit.importance).append('\n')
            append("OS の説明：").append(exit.description).append('\n')
            append("記録した時点の").append(device)
        }
        val files = buildMap {
            put(EXIT, body.toByteArray())
            log?.takeIf { it.isNotBlank() }?.let { put(LOG, it.toByteArray()) }
            trace?.let { put(if (exit.cause == ExitCause.NATIVE_CRASH) TOMBSTONE else TRACE, it) }
        }
        return CrashReport(exit.time, exit.processKey, label, files)
    }
}
