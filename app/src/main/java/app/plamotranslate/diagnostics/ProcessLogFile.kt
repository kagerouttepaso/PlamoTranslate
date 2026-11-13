package app.plamotranslate.diagnostics

import app.plamotranslate.log.LogLevel
import app.plamotranslate.log.LogSink
import app.plamotranslate.pipeline.diagnostics.ProcessLogs
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * プロセスごとのログのファイルへの書き先（docs/設計/アーキテクチャ.md「ログ」。名前と片付けの規則は中核の [ProcessLogs]）。
 * 端末の logcat はアプリのログを書かないことがある（REDMAGIC は既定で黙らせている）ので、アプリが自分で残す。
 *
 * 1行ごとに OS へ渡す（`FileOutputStream.write` は溜めない）。プロセスがネイティブの落ち方や OS の SIGKILL で終わっても、
 * OS へ渡した行はファイルに残る。大きさが [maxBytes] を超えたら、今の分を1つ前の分として残して書き直す
 * （[ProcessLogs.needsRotation]）。書くのは呼んだスレッドで、ほかのスレッドとは1行ずつ順に書く。
 */
class ProcessLogFile(
    private val dir: File,
    private val key: String,
    private val maxBytes: Long = ProcessLogs.MAX_BYTES,
) : LogSink {
    private val time = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault())
    private var out: FileOutputStream? = null
    private var size = 0L

    @Synchronized
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val line = buildString {
            append(time.format(Instant.now())).append(' ').append(level.name.first()).append(' ')
            append(Thread.currentThread().name).append(": ").append(message).append('\n')
            error?.let { append(it.stackTraceToString()).append('\n') }
        }.toByteArray()
        if (out == null) open()
        if (ProcessLogs.needsRotation(size, line.size, maxBytes)) {
            // 閉じたストリームを持ち続けないよう、先に手放す（開き直しに失敗しても、次の行で開き直す）
            out?.close()
            out = null
            Files.move(
                File(dir, ProcessLogs.current(key)).toPath(),
                File(dir, ProcessLogs.previous(key)).toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            open(append = false)
        }
        out!!.write(line)
        size += line.size
    }

    /** このプロセスのログ（1つ前の分と今の分をつないだもの）。書き出しに使う。 */
    @Synchronized
    fun read(): ByteArray = read(dir, key) ?: ByteArray(0)

    private fun open(append: Boolean = true): FileOutputStream {
        dir.mkdirs()
        val file = File(dir, ProcessLogs.current(key))
        size = if (append && file.exists()) file.length() else 0L
        return FileOutputStream(file, append).also { out = it }
    }

    companion object {
        /** [key] のプロセスのログ（1つ前の分と今の分をつないだもの）。ファイルが無ければ null。 */
        fun read(dir: File, key: String): ByteArray? {
            val parts = ProcessLogs.filesOf(key).map { File(dir, it) }.filter { it.exists() }
            if (parts.isEmpty()) return null
            return parts.fold(ByteArray(0)) { acc, f -> acc + runCatching { f.readBytes() }.getOrDefault(ByteArray(0)) }
        }

        /** 取り込みの後に、要らなくなったログを消す（[ProcessLogs.toDelete]）。 */
        fun cleanUp(dir: File, currentKey: String, seenKeys: Set<String>) {
            val files = dir.listFiles().orEmpty().associate { it.name to it.lastModified() }
            ProcessLogs.toDelete(files, currentKey, seenKeys).forEach { File(dir, it).delete() }
        }
    }
}
