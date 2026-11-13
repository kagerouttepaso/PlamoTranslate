package app.plamotranslate.diagnostics

import app.plamotranslate.log.LogLevel
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** プロセスごとのログのファイル（[ProcessLogFile]）。 */
class ProcessLogFileTest {
    private val dir: File = Files.createTempDirectory("logs").toFile()

    @After
    fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun ByteArray?.lines() = this?.toString(Charsets.UTF_8)?.lines()?.filter { it.isNotBlank() }.orEmpty()

    @Test
    fun 行ごとに書き次のプロセスから読める() {
        val log = ProcessLogFile(dir, "m1")
        log.write(LogLevel.INFO, "PlamoTranslate", "始めた", null)
        log.write(LogLevel.WARN, "PlamoTranslate", "失敗", IllegalStateException("理由"))
        // 閉じずに（落ちたときと同じく）次のプロセスが読む
        val lines = ProcessLogFile.read(dir, "m1").lines()
        assertTrue(lines[0].endsWith("始めた"))
        assertTrue(lines[1].contains(" W ") && lines[1].endsWith("失敗"))
        assertTrue(lines[2].contains("IllegalStateException: 理由"))
        assertNull(ProcessLogFile.read(dir, "m2"))
    }

    @Test
    fun 上限を超えたら1つ前の分を残して書き直し古い順につないで読む() {
        val log = ProcessLogFile(dir, "m1", maxBytes = 120)
        for (i in 1..6) log.write(LogLevel.INFO, "PlamoTranslate", "行$i ${"x".repeat(20)}", null)
        val lines = ProcessLogFile.read(dir, "m1").lines()
        // 書き直しても、残った行は途切れずに古い順に続き、最後の行で終わる（1つ前の分は上書きして回す）
        val numbers = lines.map { Regex("行(\\d)").find(it)!!.groupValues[1].toInt() }
        assertEquals(6, numbers.last())
        assertTrue(numbers.size >= 2)
        assertEquals((numbers.first()..6).toList(), numbers)
        assertTrue(File(dir, "m1.1.log").exists())
    }

    @Test
    fun 終わり方を見たプロセスのログを消す() {
        // 前のプロセスのファイル（プロセスは終わっていて、ファイルは閉じている）
        File(dir.apply { mkdirs() }, "old.log").writeText("前")
        File(dir, "old.1.log").writeText("前の前")
        val now = ProcessLogFile(dir, "now")
        now.write(LogLevel.INFO, "PlamoTranslate", "今", null)
        // 前のプロセスの終わり方を OS の記録で見たので消す。見ていないものは残す
        File(dir, "unseen.log").writeText("まだ")
        ProcessLogFile.cleanUp(dir, "now", seenKeys = setOf("old"))
        assertEquals(setOf("now.log", "unseen.log"), dir.list()!!.toSet())
        assertTrue(now.read().toString(Charsets.UTF_8).contains("今"))
    }
}
