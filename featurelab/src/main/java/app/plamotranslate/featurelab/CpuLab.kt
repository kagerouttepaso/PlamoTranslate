package app.plamotranslate.featurelab

import android.app.Application
import android.os.Handler
import android.os.HandlerThread
import java.io.File

class LabApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CpuLab.start()
    }
}

object CpuLab {
    private val early = HandlerThread("FeatureLab-early")
    fun start() = early.start()

    private external fun reset(): Int

    private fun allowed(task: File) = task.resolve("status").useLines { lines ->
        lines.first { it.startsWith("Cpus_allowed_list:") }.substringAfter(':').trim()
    }

    private fun probe(label: String) {
        val task = File("/proc/thread-self")
        val before = allowed(task)
        val error = reset()
        Lab.log("$label Cpus_allowed_list: $before → ${allowed(task)} errno=$error")
    }

    fun run() {
        System.loadLibrary("featurelab")
        Thread({
            File("/proc/self/task").listFiles().orEmpty().forEach { task ->
                runCatching {
                    val group = task.resolve("cgroup").readLines().joinToString()
                    Lab.log("tid=${task.name} Cpus_allowed_list=${allowed(task)} cgroup=$group")
                }
            }
            probe("命令時に作成")
            Handler(early.looper).post { probe("起動時に作成") }
        }, "FeatureLab-late").start()
    }
}
