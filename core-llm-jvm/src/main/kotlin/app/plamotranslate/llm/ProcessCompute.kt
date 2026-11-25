package app.plamotranslate.llm

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeScheduler
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.CpuTopology
import java.io.File

object ProcessCompute : ComputeResources {
    private val scheduler = ComputeScheduler { topology, gpu, count, waited ->
        Logs.i("計算資源 GPU=$gpu CPU=${topology.key} 予算=${topology.budget} 貸出=$count 待ち=${waited / 1_000_000}ms")
    }

    fun topology(): CpuTopology {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val pairs = LlamaNative.nativeWindowsCoreClasses().orEmptyPairs()
            return CpuTopology(pairs.ifEmpty { mapOf(0 to null) })
        }
        val allowed = runCatching {
            File("/proc/thread-self/status").useLines { lines -> lines.first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim() }
                .split(',').flatMap { range ->
                    val ends = range.split('-')
                    (ends.first().toInt()..ends.last().toInt()).toList()
                }
        }.getOrDefault(listOf(0))
        fun values(file: String) = allowed.associateWith { cpu ->
            runCatching { File("/sys/devices/system/cpu/cpu$cpu/$file").readText().trim().toLong() }.getOrNull()
        }
        val capacity = values("cpu_capacity")
        return CpuTopology(if (capacity.containsValue(null)) values("cpufreq/cpuinfo_max_freq") else capacity)
    }

    private fun IntArray?.orEmptyPairs() = this?.asList()?.chunked(2)?.associate { it[0] to it[1].toLong() }.orEmpty()

    override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
        action: suspend (Int, () -> Boolean) -> T): T =
        scheduler.borrow(topology(), gpu, threads, work, action)
}
