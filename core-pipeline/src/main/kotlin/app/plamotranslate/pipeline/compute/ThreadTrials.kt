package app.plamotranslate.pipeline.compute

import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 一つの候補でならした後に同じ入力を3回測る。中央値の絶対偏差を通常の揺れ幅とする。
 * ならしにはスレッド数ごとに違う初回だけの費用（スレッドや作業領域の用意）が入るので、比べるのは本計測だけにする。
 * 本計測の2回が計測済みの最速候補の最も遅い回より遅ければ、中央値がその回を超えて負けが決まるので残りを測らない。
 * 1回だけの遅れ（メモリの回収などほかのプロセスによる一時の止まり）では閉じない。
 */
class ThreadTrials {
    private val samples = sortedMapOf<Int, List<Long>>()
    private val fastest get() = samples.values.minByOrNull { it[1] }

    suspend fun measure(count: Int, sample: suspend () -> Long, log: (String) -> Unit): Long? {
        val warmup = sample()
        val slowestOfFastest = fastest?.last()
        val measured = mutableListOf<Long>()
        repeat(3) {
            measured += sample()
            if (slowestOfFastest != null && measured.count { it > slowestOfFastest } >= 2) {
                log("${count}本 ならし=${warmup}ns 本計測=${measured}ns 最速の最も遅い回=${slowestOfFastest}ns 打ち切り")
                return null
            }
        }
        val times = measured.sorted()
        samples[count] = times
        log("${count}本 ならし=${warmup}ns 本計測=${times}ns")
        return times[1]
    }

    val fastestMedian get() = fastest?.get(1) ?: Long.MAX_VALUE

    internal fun measuredMedian(count: Int) = samples[count]?.get(1)

    fun median(count: Int) = samples.getValue(count)[1]

    private fun withinDeviation(times: List<Long>): Boolean {
        val best = checkNotNull(fastest)
        fun deviation(values: List<Long>) = minOf(values[1] - values[0], values[2] - values[1])
        return times[1] - best[1] <= maxOf(deviation(times), deviation(best))
    }

    suspend fun search(boundaries: List<Int>, range: IntRange, measure: suspend (Int) -> Unit) {
        require(range.first == 1) { "スレッド数の探索範囲は1から始めます" }
        require(boundaries.isNotEmpty() && boundaries.all { it in range })
        val visited = mutableSetOf<Int>()
        suspend fun visit(count: Int) {
            if (visited.add(count)) measure(count)
        }
        for (count in boundaries) visit(count)
        val peak = samples.minBy { it.value[1] }.key
        for (count in peak - 1 downTo range.first) {
            visit(count)
            if (samples[count]?.let(::withinDeviation) != true) break
        }
        for (count in peak + 1..range.last) {
            val previousBest = fastestMedian
            visit(count)
            if (fastestMedian >= previousBest) break
        }
    }

    fun preferred(): Int {
        return samples.entries.first { withinDeviation(it.value) }.key
    }

    fun encode() = samples.entries.joinToString("\n") { (n, times) -> (listOf(n) + times).joinToString(",") }

    companion object {
        fun decode(data: String): ThreadTrials = ThreadTrials().apply {
            data.lines().forEach { line ->
                val values = line.split(',').map(String::toLong)
                require(values.size == 4 && values.first() in 1..Int.MAX_VALUE.toLong())
                val times = values.drop(1)
                require(times.first() >= 0 && times == times.sorted())
                samples[values.first().toInt()] = times
            }
            require(samples.isNotEmpty())
        }
    }
}

class ThreadBenchmark(val purpose: Purpose, val component: String, val version: String, private val directory: File?,
    val sample: suspend (Int) -> Long) {
    enum class Purpose(val label: String) {
        Transcription("音声認識"), LanguageIdentification("言語の判定"), Translation("翻訳"), SpeechSynthesis("読み上げ")
    }

    suspend fun select(board: ThreadTrialBoard, cpuKey: String, candidates: List<Int>, valid: () -> Boolean,
        range: IntRange = 1..candidates.max(), measure: suspend (ThreadTrials, Int) -> Unit): ThreadTrials {
        val key = listOf(directory?.absolutePath, component, version, cpuKey, candidates, range, SEARCH_VERSION).joinToString("|")
        val file = directory?.resolve(UUID.nameUUIDFromBytes(key.toByteArray()).toString())
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val cached = if (file == null) null else cache[key] ?: runCatching { ThreadTrials.decode(file.readText()) }.getOrNull()
            if (cached != null && cached.preferred() in range) {
                cache[key] = cached
                return@withLock cached
            }
            val trials = ThreadTrials()
            val id = UUID.randomUUID().toString()
            val rows = MutableList<ThreadTrialRow>(range.last) { ThreadTrialRow.Pending }
            fun publish(selected: Int? = null) {
                board.publish(id, ThreadTrialProgress(id, purpose, rows.toList(), selected))
            }
            try {
                trials.search(candidates, range) { count ->
                    rows[count - 1] = ThreadTrialRow.Measuring
                    publish()
                    measure(trials, count)
                    rows[count - 1] = trials.measuredMedian(count)?.let { ThreadTrialRow.Measured(it) }
                        ?: ThreadTrialRow.CutOff
                    publish()
                }
                publish(trials.preferred())
            } finally {
                board.publish(id, null)
            }
            if (valid() && file != null) {
                cache[key] = trials
                runCatching {
                    file.parentFile.mkdirs()
                    val temp = File.createTempFile("threads-", ".tmp", file.parentFile)
                    try {
                        temp.writeText(trials.encode())
                        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                    } finally {
                        temp.delete()
                    }
                }
            }
            trials
        }
    }

    companion object {
        const val SEARCH_VERSION = "mad-5"
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ThreadTrials>()
        private val locks = mutableMapOf<String, Mutex>()
    }
}
