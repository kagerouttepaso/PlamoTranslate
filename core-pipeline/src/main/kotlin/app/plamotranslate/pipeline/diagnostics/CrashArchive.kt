package app.plamotranslate.pipeline.diagnostics

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 書き出すファイル（zip）の組み立て。記録を `records/<記録>/` に、書き出した時点のもの（[write] の now）を `now/` に入れる。 */
object CrashArchive {
    /** [read] は記録のファイルの中身を読む（保存の実装が渡す）。 */
    fun write(entries: List<CrashEntry>, read: (CrashEntry, String) -> ByteArray, now: Map<String, ByteArray>, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            fun put(name: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
            for (entry in entries) {
                val head = "題：${entry.title}\n時刻：${CrashReports.timeText(entry.time)}\nプロセス：${entry.process}\n"
                put("records/${entry.id}/entry.txt", head.toByteArray())
                entry.files.forEach { name -> runCatching { read(entry, name) }.onSuccess { put("records/${entry.id}/$name", it) } }
            }
            now.forEach { (name, bytes) -> put("now/$name", bytes) }
        }
    }

    /** 書き出すファイルの名前。 */
    fun fileName(time: Long): String =
        "mockingword-diagnostics-${CrashReports.timeText(time).filter { it.isDigit() }.take(14)}.zip"
}
