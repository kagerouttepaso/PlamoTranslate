package app.plamotranslate.pipeline.speech

import java.nio.ByteBuffer
import java.nio.ByteOrder

object SpeechTrialAudio {
    val reference = TranscriptionTuner.Reference("The door is open.", "en")
    const val version = "door-en-1"
    val samples by lazy {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/asr/reference.s16le")).use { it.readBytes() }
        val shorts = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        FloatArray(shorts.remaining()) { shorts.get() / 32768f }
    }
}
