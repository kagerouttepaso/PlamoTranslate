package app.plamotranslate

import app.plamotranslate.pipeline.compute.ComputeWork
import app.plamotranslate.pipeline.compute.ThreadBenchmark
import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.ocr.OcrCompute
import app.plamotranslate.tts.TtsCompute

internal object AndroidCpuCompute : OcrCompute, TtsCompute {
    override fun prepareThread() = ProcessCompute.resetCpuAffinity()

    override suspend fun <T> measured(component: String, version: String, directory: java.io.File,
        sample: suspend (Int) -> Long, action: suspend (Int) -> T): T {
        val benchmark = ThreadBenchmark(component, version, directory.resolve("thread-profiles"), sample)
        val work = ComputeWork(name = component, benchmark = benchmark)
        return ProcessCompute.borrow(false, work = work) { n, _ -> action(n) }
    }

    override suspend fun <T> exclusive(action: suspend () -> T): T =
        ProcessCompute.borrow(false, Int.MAX_VALUE, ComputeWork(name = "文字認識")) { _, _ -> action() }
}
