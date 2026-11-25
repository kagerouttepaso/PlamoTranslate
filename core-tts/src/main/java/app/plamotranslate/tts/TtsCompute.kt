package app.plamotranslate.tts

interface TtsCompute {
    suspend fun <T> borrow(threads: Int, action: suspend (Int) -> T): T
}
