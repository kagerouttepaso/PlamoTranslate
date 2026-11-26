package app.plamotranslate.llm

import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.compute.ComputeResources
import app.plamotranslate.pipeline.compute.ComputeScheduler
import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.CpuTopology
import java.io.File

object ProcessCompute : ComputeResources {
    private val scheduler = ComputeScheduler { Logs.i(it) }

    fun resetCpuAffinity() {
        if (System.getProperty("java.vm.name") == "Dalvik") LlamaNative.nativeResetCpuAffinity()
    }

    fun topology(): CpuTopology {
        if (System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
            val pairs = LlamaNative.nativeWindowsCoreClasses()?.asList()?.chunked(2)?.associate { (cpu, efficiency) ->
                cpu to CpuTopology.Core(efficiency, efficiency.toLong())
            }.orEmpty()
            return CpuTopology(pairs.ifEmpty { mapOf(0 to null) })
        }
        resetCpuAffinity()
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

    private class TopologyChanged : Exception()

    override suspend fun selectThreads(benchmark: ThreadBenchmark, requested: Int, gpu: Boolean, capacity: Int): Int {
        while (true) {
            val topology = topology()
            val candidates = topology.candidates(requested).map { minOf(it, capacity) }.distinct()
            if (candidates.size == 1) return candidates.single()
            try {
                return benchmark.select(topology.key, candidates, { topology().key == topology.key }) { trials, n ->
                    borrow(gpu, n, ComputeWork(name = "${benchmark.component} 試走", exclusiveCpu = true)) { available, _ ->
                        if (n > available || topology().key != topology.key) throw TopologyChanged()
                        trials.measure(n, { benchmark.sample(n) }) { Logs.i("${benchmark.component} CPU=${topology.key} $it") }
                    }
                }.preferred()
            } catch (_: TopologyChanged) {
                // 異なる CPU の組で測った時間を混ぜず、新しい候補から選ぶ。
            }
        }
    }

    override suspend fun <T> borrow(gpu: Boolean, threads: Int, work: ComputeWork,
        action: suspend (Int, () -> Boolean) -> T): T =
        scheduler.borrow(topology(), gpu, work.benchmark?.let { selectThreads(it, threads, gpu) } ?: threads, work) { count, waiting ->
            resetCpuAffinity()
            action(count, waiting)
        }
}
