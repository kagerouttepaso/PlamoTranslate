package app.plamotranslate.pipeline.compute

import java.io.File
import java.util.UUID
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 一つの候補でならした後に同じ入力を3回測る。中央値の絶対偏差を通常の揺れ幅とする。 */
class ThreadTrials {
    private var bestWarmup = Long.MAX_VALUE
    private var bestMedian = Long.MAX_VALUE
    private val samples = sortedMapOf<Int, List<Long>>()

    suspend fun measure(count: Int, sample: suspend () -> Long, log: (String) -> Unit): Long? {
        val warmup = sample()
        if (warmup > bestWarmup) {
            log("${count}本 ならし=${warmup}ns 打ち切り")
            return null
        }
        val times = List(3) { sample() }.sorted()
        samples[count] = times
        val median = times[1]
        log("${count}本 ならし=${warmup}ns 本計測=${times}ns")
        if (median < bestMedian) {
            bestWarmup = warmup
            bestMedian = median
        }
        return median
    }

    val fastestMedian get() = bestMedian

    fun median(count: Int) = samples.getValue(count)[1]

    fun preferred(): Int {
        val fastest = samples.values.minBy { it[1] }
        fun deviation(times: List<Long>) = minOf(times[1] - times[0], times[2] - times[1])
        return samples.entries.first { (_, times) ->
            times[1] - fastest[1] <= maxOf(deviation(times), deviation(fastest))
        }.key
    }

    fun encode() = "$bestWarmup,$bestMedian\n" + samples.entries.joinToString("\n") { (n, times) -> (listOf(n) + times).joinToString(",") }

    companion object {
        fun decode(data: String): ThreadTrials = ThreadTrials().apply {
            val lines = data.lines()
            val baseline = lines.first().split(',').map(String::toLong)
            require(baseline.size == 2)
            bestWarmup = baseline.first()
            bestMedian = baseline.last()
            lines.drop(1).forEach { line ->
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

class ThreadBenchmark(val component: String, val version: String, private val directory: File?,
    val sample: suspend (Int) -> Long) {
    suspend fun select(cpuKey: String, candidates: List<Int>, valid: () -> Boolean,
        measure: suspend (ThreadTrials, Int) -> Unit): ThreadTrials {
        val key = listOf(directory?.absolutePath, component, version, cpuKey, candidates, "mad-2").joinToString("|")
        val file = directory?.resolve(UUID.nameUUIDFromBytes(key.toByteArray()).toString())
        val lock = synchronized(locks) { locks.getOrPut(key) { Mutex() } }
        return lock.withLock {
            val cached = if (file == null) null else cache[key] ?: runCatching { ThreadTrials.decode(file.readText()) }.getOrNull()
            if (cached != null && cached.preferred() in candidates) {
                cache[key] = cached
                return@withLock cached
            }
            val trials = ThreadTrials()
            candidates.forEach { measure(trials, it) }
            trials.preferred()
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
        private val cache = java.util.concurrent.ConcurrentHashMap<String, ThreadTrials>()
        private val locks = mutableMapOf<String, Mutex>()
    }
}
