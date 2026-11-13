package app.plamotranslate.pipeline.diagnostics

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CrashJournalsTest {
    private fun entry(id: String, time: Long, process: String, vararg files: String) = CrashEntry(id, time, process, "題", files.toList())

    @Test
    fun 同じプロセスの記録に足す() {
        val entries = listOf(entry("a", 1, "p1"), entry("b", 2, "p2"))
        val report = CrashReports.ofExit(ProcessExit(5, 9, ExitCause.EXCEPTION, mark = ProcessMark("p2")), "端末", null, null)
        assertEquals("b", CrashJournals.target(entries, report)?.id)
        val other = CrashReports.ofExit(ProcessExit(5, 9, ExitCause.EXCEPTION, mark = ProcessMark("p3")), "端末", null, null)
        assertNull(CrashJournals.target(entries, other))
    }

    @Test
    fun 同じ名前のファイルは捨てずに名前を変えて足す() {
        assertEquals("exit.txt", CrashJournals.fileName(listOf("report.txt"), "exit.txt"))
        assertEquals("exit-2.txt", CrashJournals.fileName(listOf("exit.txt"), "exit.txt"))
        assertEquals("exit-3.txt", CrashJournals.fileName(listOf("exit.txt", "exit-2.txt"), "exit.txt"))
        assertEquals("trace-2", CrashJournals.fileName(listOf("trace"), "trace"))
    }

    @Test
    fun 上限を超えたら古いものから消す() {
        val entries = listOf(entry("a", 1, "p1"), entry("c", 3, "p3"), entry("b", 2, "p2"))
        assertEquals(listOf("a"), CrashJournals.toDrop(entries, keep = 2).map { it.id })
    }

    @Test
    fun フォルダーの名前は時刻の順に並ぶ() {
        val report = CrashReports.ofExit(ProcessExit(5, 9, ExitCause.EXCEPTION), "端末", null, null)
        assertEquals("0000000000005-pid9", CrashJournals.folderName(report))
    }

    @Test
    fun 例外の記録とOSの記録は同じプロセスの鍵を持つ() {
        val mark = ProcessMark("m1")
        val exception = CrashReports.ofException(1, 9, mark, "main", IllegalStateException(), "端末")
        val exit = CrashReports.ofExit(ProcessExit(2, 9, ExitCause.EXCEPTION, mark = mark), "端末", "log".toByteArray(), null)
        assertEquals(exception.process, exit.process)
        assertEquals(setOf(CrashReports.EXIT, CrashReports.LOG), exit.files.keys)
        val native = CrashReports.ofExit(ProcessExit(2, 9, ExitCause.NATIVE_CRASH), "端末", ByteArray(0), byteArrayOf(1))
        assertEquals(setOf(CrashReports.EXIT, CrashReports.TOMBSTONE), native.files.keys)
    }

    @Test
    fun 書き出しは記録と今の様子をzipに入れ読めないファイルは飛ばす() {
        val entries = listOf(entry("0001-p1", 1, "p1", "exit.txt", "gone.txt"))
        val out = ByteArrayOutputStream()
        CrashArchive.write(
            entries,
            read = { _, name -> if (name == "gone.txt") error("消えた") else name.toByteArray() },
            now = mapOf(CrashReports.NOW_LOG to "now".toByteArray()),
            out = out,
        )
        val names = mutableListOf<String>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip -> while (true) names += (zip.nextEntry ?: break).name }
        assertEquals(listOf("records/0001-p1/entry.txt", "records/0001-p1/exit.txt", "now/log.txt"), names)
        assertTrue(CrashArchive.fileName(0).startsWith("mockingword-diagnostics-"))
    }

    @Test
    fun ログのファイルは鍵ごとに古い順に並び今のプロセスのもの以外を消す() {
        assertEquals(listOf("m1.1.log", "m1.log"), ProcessLogs.filesOf("m1"))
        assertEquals("m1", ProcessLogs.keyOf("m1.log"))
        assertEquals("m1", ProcessLogs.keyOf("m1.1.log"))
        assertNull(ProcessLogs.keyOf("note.txt"))
        assertTrue(ProcessLogs.needsRotation(size = 100, lineBytes = 30, maxBytes = 120))
        assertTrue(!ProcessLogs.needsRotation(size = 0, lineBytes = 500, maxBytes = 120))
        assertTrue(!ProcessLogs.needsRotation(size = 90, lineBytes = 30, maxBytes = 120))
    }

    @Test
    fun 終わり方を見たプロセスのログを消し見ていないものは新しい順に残す() {
        val files = mapOf(
            "now.log" to 900L,
            "seen.log" to 800L, "seen.1.log" to 700L,
            "u1.log" to 600L, "u2.log" to 500L, "u3.log" to 400L, "u4.log" to 300L,
            "x.txt" to 100L,
        )
        val deleted = ProcessLogs.toDelete(files, currentKey = "now", seenKeys = setOf("seen", "gone"), keepUnseen = 2)
        assertEquals(setOf("seen.log", "seen.1.log", "u3.log", "u4.log", "x.txt"), deleted.toSet())
        assertEquals("pid9", ProcessExit.keyOf(null, 9))
        assertEquals("m3", ProcessExit.keyOf(ProcessMark("m3"), 9))
    }
}
