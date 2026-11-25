package app.plamotranslate

import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.ocr.OcrCompute
import app.plamotranslate.tts.TtsCompute

internal object AndroidCpuCompute : OcrCompute, TtsCompute {
    override suspend fun <T> borrow(threads: Int, action: suspend (Int) -> T): T =
        ProcessCompute.borrow(false, threads) { n, _ -> action(n) }
}
