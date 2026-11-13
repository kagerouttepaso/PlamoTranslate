package app.plamotranslate.diagnostics

import app.plamotranslate.pipeline.diagnostics.CrashReports
import app.plamotranslate.pipeline.diagnostics.ExitCause
import app.plamotranslate.pipeline.diagnostics.ProcessExit
import app.plamotranslate.pipeline.diagnostics.ProcessMark
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.concurrent.Executor
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** ファイルに置く不具合の記録（[FileCrashJournal]）。裏のスレッドの代わりに、その場で動かす。 */
class FileCrashJournalTest {
    private val dir: File = Files.createTempDirectory("journal").toFile()
    private val now = Executor { it.run() }

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun exit(time: Long, pid: Int, mark: String?, cause: ExitCause = ExitCause.EXCEPTION) =
        ProcessExit(time, pid, cause, mark = mark?.let { ProcessMark(it) })

    @Test
    fun 落ちる直前の記録と次の起動で知った終わり方は同じプロセスの1件にまとめる() {
        val journal = FileCrashJournal(dir, worker = now)
        journal.recordNow(CrashReports.ofException(1_000L, 42, ProcessMark("m1"), "main", IllegalStateException("x"), "端末"))
        // 次の起動：記録を読み直してから、OS の記録を足す
        val next = FileCrashJournal(dir, worker = now)
        next.record(CrashReports.ofExit(exit(100_000L, 42, "m1"), "端末", log = "log".toByteArray(), trace = null))
        val entry = next.entries.value.single()
        assertEquals("例外で落ちた（IllegalStateException）", entry.title)
        assertEquals(listOf(CrashReports.EXIT, CrashReports.LOG, CrashReports.REPORT), entry.files)
    }

    @Test
    fun 同じ名前のファイルが来ても捨てない() {
        val journal = FileCrashJournal(dir, worker = now)
        journal.record(CrashReports.ofExit(exit(1_000L, 1, "m1"), "端末", null, null))
        journal.record(CrashReports.ofExit(exit(2_000L, 1, "m1"), "端末", null, null))
        assertEquals(listOf("exit-2.txt", "exit.txt"), journal.entries.value.single().files)
    }

    @Test
    fun 上限を超えたら古いものから消し開き直しても一覧が戻る() {
        val journal = FileCrashJournal(dir, keep = 2, worker = now)
        for (i in 1..3) journal.record(CrashReports.ofExit(exit(i * 1_000L, i, "m$i"), "端末", null, null))
        assertEquals(listOf("m3", "m2"), journal.entries.value.map { it.process })
        assertEquals(listOf("m3", "m2"), FileCrashJournal(dir, keep = 2, worker = now).entries.value.map { it.process })
        journal.clear()
        assertTrue(journal.entries.value.isEmpty())
        assertTrue(FileCrashJournal(dir, worker = now).entries.value.isEmpty())
    }

    @Test
    fun 壊れた記録は飛ばして読む() {
        File(dir, "0000000000001-broken").mkdirs()
        val journal = FileCrashJournal(dir, worker = now)
        journal.record(CrashReports.ofExit(exit(5_000L, 7, "m7"), "端末", null, null))
        assertEquals(listOf("m7"), journal.entries.value.map { it.process })
    }

    @Test
    fun 書き出しは記録と今の様子をzipに入れる() = runBlocking {
        val journal = FileCrashJournal(dir, worker = now)
        journal.record(CrashReports.ofExit(exit(5_000L, 7, "m7"), "端末", "log".toByteArray(), null))
        val out = ByteArrayOutputStream()
        journal.export(mapOf(CrashReports.NOW_LOG to "now".toByteArray()), out)
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        val id = journal.entries.value.single().id
        assertEquals(listOf("records/$id/entry.txt", "records/$id/exit.txt", "records/$id/log.txt", "now/log.txt"), names)
    }
}
