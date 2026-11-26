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

    fun median(count: Int) = samples.getValue(count)[1]

    fun preferred(): Int {
        val fastest = samples.values.minBy { it[1] }
        fun deviation(times: List<Long>) = minOf(times[1] - times[0], times[2] - times[1])
        return samples.entries.first { (_, times) ->
            times[1] - fastest[1] <= maxOf(deviation(times), deviation(fastest))
        }.key
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

class ThreadBenchmark(val component: String, val version: String, private val directory: File?,
    val sample: suspend (Int) -> Long) {
    suspend fun select(cpuKey: String, candidates: List<Int>, valid: () -> Boolean,
        measure: suspend (ThreadTrials, Int) -> Unit): ThreadTrials {
        val key = listOf(directory?.absolutePath, component, version, cpuKey, candidates, "mad-4").joinToString("|")
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
