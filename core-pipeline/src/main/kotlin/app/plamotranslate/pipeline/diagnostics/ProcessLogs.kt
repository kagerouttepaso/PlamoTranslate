package app.plamotranslate.pipeline.diagnostics

/**
 * プロセスごとのログのファイルの規則（docs/設計/アーキテクチャ.md「ログ」）。アプリのログは、プロセスの鍵
 * （[ProcessExit.processKey]）ごとのファイルに書き、大きさが [MAX_BYTES] を超えたら1つ前の分として残して書き直す。
 * 次の起動で、取り込む終わり方と同じ鍵のファイルを記録に添え、OS の記録で終わり方を見たプロセスのファイルを消す。
 */
object ProcessLogs {
    /** 1つのファイルの大きさの上限。1つ前の分と合わせて、1プロセスで最大この2倍を残す。 */
    const val MAX_BYTES = 256L * 1024

    /** 終わり方をまだ OS の記録で見ていないプロセスのファイルを、新しい順にいくつまで残すか。 */
    const val KEEP_UNSEEN = 3

    /** 今書いているファイルの名前。 */
    fun current(key: String): String = "$key.log"

    /** 上限を超えたときに残す、1つ前の分のファイルの名前。 */
    fun previous(key: String): String = "$key.1.log"

    /** 鍵のファイルを、古い順（1つ前の分、今の分）に並べる。 */
    fun filesOf(key: String): List<String> = listOf(previous(key), current(key))

    /** 大きさ [size] のファイルに [lineBytes] の行を足す前に、書き直すか。空のファイルには、上限を超える行でもそのまま書く。 */
    fun needsRotation(size: Long, lineBytes: Int, maxBytes: Long = MAX_BYTES): Boolean = size > 0 && size + lineBytes > maxBytes

    /** ファイルの名前から鍵を読む。ログのファイルでなければ null。 */
    fun keyOf(fileName: String): String? = when {
        fileName.endsWith(".1.log") -> fileName.removeSuffix(".1.log")
        fileName.endsWith(".log") -> fileName.removeSuffix(".log")
        else -> null
    }.takeIf { !it.isNullOrEmpty() }

    /**
     * 取り込みの後に消すファイル。[files] はファイルの名前と更新の時刻、[seenKeys] は OS の記録で終わり方を見たプロセスの鍵。
     *
     * - 今のプロセス（[currentKey]）のものは消さない。
     * - 終わり方を見たプロセスのものは、記録に添えたかどうかに関わらず消す（もう添える先が無い）。
     * - 終わり方をまだ見ていないプロセスのものは、次の起動で添えられるよう、新しい [keepUnseen] 件まで残す。
     *   OS の記録が遅れて出ても、ログが先に消えないようにするため。
     * - ログのファイルでないものは消す。
     */
    fun toDelete(files: Map<String, Long>, currentKey: String, seenKeys: Set<String>, keepUnseen: Int = KEEP_UNSEEN): List<String> {
        val byKey = files.keys.groupBy { keyOf(it) }
        val unseen = byKey.keys.filterNotNull().filter { it != currentKey && it !in seenKeys }
            .sortedByDescending { key -> byKey.getValue(key).maxOf { files.getValue(it) } }
        val keep = setOf(currentKey) + unseen.take(keepUnseen)
        return files.keys.filter { keyOf(it) !in keep }
    }
}
