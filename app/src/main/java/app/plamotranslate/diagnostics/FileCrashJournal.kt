package app.plamotranslate.diagnostics

import app.plamotranslate.pipeline.diagnostics.CrashArchive
import app.plamotranslate.pipeline.diagnostics.CrashEntry
import app.plamotranslate.pipeline.diagnostics.CrashJournal
import app.plamotranslate.pipeline.diagnostics.CrashJournals
import app.plamotranslate.pipeline.diagnostics.CrashReport
import java.io.File
import java.io.OutputStream
import java.util.Properties
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * ファイルに置く [CrashJournal]（外とつなぐ部品。規則は中核の [CrashJournals] に従う）。
 * 1件を1つのフォルダー（[CrashJournals.folderName]）にし、題・時刻・プロセスの鍵を `entry.properties` に書く。
 *
 * 命令（[record]・[clear]）と最初の読み込みは、1本の裏のスレッド（[worker]）で順に行う。ファイルに触る処理は [lock] で
 * 1つずつにし、裏のスレッドの書き込み・落ちる直前の書き込み（[recordNow]）・書き出し（[export]）が重ならないようにする。
 * 壊れた記録のフォルダーは飛ばして読む。
 */
class FileCrashJournal(
    private val dir: File,
    private val keep: Int = 20,
    private val worker: Executor = Executors.newSingleThreadExecutor { Thread(it, "CrashJournal").apply { isDaemon = true } },
) : CrashJournal {
    private val lock = Any()
    private val state = MutableStateFlow<List<CrashEntry>>(emptyList())
    override val entries: StateFlow<List<CrashEntry>> = state.asStateFlow()

    init {
        // 作るのは起動の途中（メインスレッド）なので、ディスクは裏で読む
        worker.execute { refresh() }
    }

    override fun record(report: CrashReport) {
        worker.execute {
            synchronized(lock) {
                write(report, load())
                CrashJournals.toDrop(load(), keep).forEach { File(dir, it.id).deleteRecursively() }
            }
            refresh()
        }
    }

    override fun recordNow(report: CrashReport) {
        synchronized(lock) { write(report, state.value) }
    }

    override fun clear() {
        worker.execute {
            synchronized(lock) { dir.listFiles()?.forEach { it.deleteRecursively() } }
            refresh()
        }
    }

    override suspend fun export(now: Map<String, ByteArray>, out: OutputStream) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            CrashArchive.write(load(), read = { entry, name -> File(File(dir, entry.id), name).readBytes() }, now = now, out = out)
        }
    }

    /** [report] を書く。同じプロセスの記録が [known] にあればそこへ足し、無ければ新しいフォルダーを作る。 */
    private fun write(report: CrashReport, known: List<CrashEntry>) {
        val target = CrashJournals.target(known, report)
        val folder = File(dir, target?.id ?: CrashJournals.folderName(report)).apply { mkdirs() }
        val meta = File(folder, META)
        if (!meta.exists()) {
            val p = Properties().apply {
                setProperty(TIME, report.time.toString())
                setProperty(PROCESS, report.process)
                setProperty(TITLE, report.title)
            }
            meta.writer(Charsets.UTF_8).use { p.store(it, null) }
        }
        val existing = folder.list().orEmpty().toMutableSet()
        for ((name, bytes) in report.files) {
            val unique = CrashJournals.fileName(existing, name)
            File(folder, unique).writeBytes(bytes)
            existing += unique
        }
    }

    private fun refresh() {
        state.value = synchronized(lock) { load() }
    }

    private fun load(): List<CrashEntry> = dir.listFiles { f -> f.isDirectory }.orEmpty()
        .mapNotNull { folder ->
            runCatching {
                val p = Properties().apply { File(folder, META).reader(Charsets.UTF_8).use { load(it) } }
                CrashEntry(
                    id = folder.name,
                    time = p.getProperty(TIME).toLong(),
                    process = p.getProperty(PROCESS).orEmpty(),
                    title = p.getProperty(TITLE).orEmpty(),
                    files = folder.list().orEmpty().filter { it != META }.sorted(),
                )
            }.getOrNull()
        }
        .sortedByDescending { it.time }

    private companion object {
        const val META = "entry.properties"
        const val TIME = "time"
        const val PROCESS = "process"
        const val TITLE = "title"
    }
}
