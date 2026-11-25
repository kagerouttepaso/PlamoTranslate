package app.plamotranslate.llm

import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeScheduler
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.CpuTopology
import java.io.File

object ProcessCompute : ComputeResources {
    private val scheduler = ComputeScheduler { topology, gpu, count, waited ->
        Logs.i("計算資源 GPU=$gpu CPU段(速い順)=${topology.tiers} 予算=${topology.budget} 貸出=$count 待ち=${waited / 1_000_000}ms")
    }

    fun topology(): CpuTopology {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val pairs = LlamaNative.nativeWindowsCoreClasses()?.asList()?.chunked(2)?.associate { (cpu, efficiency) ->
                cpu to CpuTopology.Core(efficiency, efficiency.toLong())
            }.orEmpty()
            return CpuTopology(pairs.ifEmpty { mapOf(0 to null) })
        }
        val allowed = runCatching {
            File("/proc/thread-self/status").useLines { lines -> lines.first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim() }
                .split(',').flatMap { range ->
                    val ends = range.split('-')
                    (ends.first().toInt()..ends.last().toInt()).toList()
                }
        }.getOrDefault(listOf(0))
        return CpuTopology(allowed.associateWith { cpu ->
            val policy = File("/sys/devices/system/cpu/cpu$cpu/cpufreq")
            runCatching {
                val related = File(policy, "related_cpus").readText().trim()
                    .split(Regex("\\s+")).map(String::toInt)
                require(cpu in related)
                val maxFrequency = File(policy, "cpuinfo_max_freq").readText().trim().toLong()
                CpuTopology.Core(related.min(), maxFrequency)
            }.getOrNull()
        })
    }

    override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
        action: suspend (Int, () -> Boolean) -> T): T =
        scheduler.borrow(topology(), gpu, threads, work, action)
}
