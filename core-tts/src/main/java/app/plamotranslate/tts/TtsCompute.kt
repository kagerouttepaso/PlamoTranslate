package app.plamotranslate.tts

interface TtsCompute {
    suspend fun <T> measured(component: String, version: String, directory: java.io.File,
        sample: suspend (Int) -> Long, action: suspend (Int) -> T): T
}
