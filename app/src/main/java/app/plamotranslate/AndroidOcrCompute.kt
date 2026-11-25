package app.plamotranslate

import app.plamotranslate.llm.ProcessCompute
import app.plamotranslate.ocr.OcrCompute

internal object AndroidOcrCompute : OcrCompute {
    override suspend fun <T> borrow(threads: Int, action: suspend (Int) -> T): T =
        ProcessCompute.borrow(false, threads) { n, _ -> action(n) }
}
