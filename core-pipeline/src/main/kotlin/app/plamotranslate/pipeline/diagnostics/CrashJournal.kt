package app.plamotranslate.pipeline.diagnostics

import java.io.OutputStream
import kotlinx.coroutines.flow.StateFlow

/** 保存した記録の1件。[files] はその記録のファイルの名前（名前の順）。 */
data class CrashEntry(val id: String, val time: Long, val process: String, val title: String, val files: List<String>)

/**
 * 不具合の記録の保存先（差し込み口。実装は `app` の `diagnostics/FileCrashJournal`）。命令と購読の部品にする。
 *
 * - 命令：[record]（1件を書く）、[clear]（全部消す）。どちらもすぐ戻り、結果は [entries] で届く。
 * - 購読：[entries]（新しい順の一覧）。
 * - 例外：[recordNow] は、例外の受け口がプロセスの終わる直前に呼ぶので、書き終えてから戻る。
 * - [export] は、書き出しの画面が裏のスレッドで呼び、zip を書き終えるまで待つ。
 */
interface CrashJournal {
    val entries: StateFlow<List<CrashEntry>>

    /** 1件を書く。同じプロセスの記録（[CrashJournals.target]）があれば、新しく作らずそこへファイルを足す。 */
    fun record(report: CrashReport)

    /**
     * 落ちる直前に1件を書き切る。書くのは記録のファイルだけにし、古い記録の片付けと一覧の読み直しはしない
     * （次の起動で行う）。メモリ不足で落ちるときにも失敗しにくいよう、仕事を減らす。
     */
    fun recordNow(report: CrashReport)

    fun clear()

    /** 記録を全部と、書き出した時点のもの（[now]）を、zip にして [out] へ書く（[CrashArchive]）。 */
    suspend fun export(now: Map<String, ByteArray>, out: OutputStream)
}

/** 記録の保存の規則（どの記録に足すか、ファイルの名前、何件残すか）。保存の実装はこれに従う。 */
object CrashJournals {
    /** [report] を足す先の記録。同じプロセスの記録が無ければ null（新しく作る）。 */
    fun target(entries: List<CrashEntry>, report: CrashReport): CrashEntry? = entries.firstOrNull { it.process == report.process }

    /** 記録の中で重ならないファイルの名前。同じ名前があれば、捨てずに `-2`・`-3` を付けて足す。 */
    fun fileName(existing: Collection<String>, name: String): String {
        if (name !in existing) return name
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        return generateSequence(2) { it + 1 }.map { "${name.substring(0, dot)}-$it${name.substring(dot)}" }.first { it !in existing }
    }

    /** 件数が [keep] を超えたときに消す記録（古いもの）。 */
    fun toDrop(entries: List<CrashEntry>, keep: Int): List<CrashEntry> = entries.sortedByDescending { it.time }.drop(keep)

    /** 記録のフォルダーの名前。時刻の順に並び、プロセスの鍵で見分けられる。 */
    fun folderName(report: CrashReport): String = "%013d-%s".format(java.util.Locale.ROOT, report.time, report.process.filter { it.isLetterOrDigit() })
}
