package app.plamotranslate.desktop

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.pipeline.speech.SpeechEngine
import app.plamotranslate.pipeline.speech.SpeechEvent
import app.plamotranslate.pipeline.speech.SpeechLanguages
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal sealed interface TextVoiceUpdate {
    data object Listening : TextVoiceUpdate
    data class Partial(val text: String) : TextVoiceUpdate
    data class Final(val text: String) : TextVoiceUpdate
}

internal class DesktopTextVoice(
    private val capture: AudioCapture,
    private val loadEngine: (DesktopRecognitionSettings) -> SpeechEngine,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    fun listen(settings: DesktopRecognitionSettings, source: Language?, finish: Deferred<Unit>) = flow {
        var engine: SpeechEngine? = null
        try {
            withContext(io) { engine = loadEngine(settings) }
            val recognizer = checkNotNull(engine)
            coroutineScope {
                val events = Channel<SpeechEvent>(Channel.UNLIMITED)
                val started = CompletableDeferred<Unit>()
                val spoken = CompletableDeferred<Unit>()
                recognizer.listener = { event ->
                    if (event is SpeechEvent.CardOpened) spoken.complete(Unit)
                    events.trySend(event)
                }
                recognizer.languages = SpeechLanguages(separateChinese = source?.isChinese == true)
                recognizer.reset()
                val input = launch { capture.capture(AudioSource.MICROPHONE, { started.complete(Unit) }, recognizer::accept) }
                val stop = launch {
                    finish.await()
                    input.cancelAndJoin()
                }
                val timeout = launch {
                    started.await()
                    delay(8_000)
                    if (!spoken.isCompleted) input.cancelAndJoin()
                }
                val flush = launch {
                    input.join()
                    recognizer.flush()
                }
                try {
                    started.await()
                    emit(TextVoiceUpdate.Listening)
                    val opened = ArrayDeque<Long>()
                    for (event in events) when (event) {
                        is SpeechEvent.CardOpened -> opened.addLast(event.card)
                        is SpeechEvent.CardText -> if (event.card == opened.firstOrNull()) emit(TextVoiceUpdate.Partial(event.text))
                        is SpeechEvent.CardFinished -> if (event.card == opened.firstOrNull()) {
                            opened.removeFirst()
                            val text = event.utterance?.text.orEmpty()
                            if (text.isNotBlank()) {
                                emit(TextVoiceUpdate.Final(text))
                                break
                            }
                        }
                        SpeechEvent.Flushed -> break
                        else -> Unit
                    }
                } finally {
                    input.cancel()
                    stop.cancel()
                    timeout.cancel()
                    flush.cancel()
                    events.cancel()
                }
            }
        } finally {
            withContext(NonCancellable + io) { engine?.close() }
        }
    }
}
