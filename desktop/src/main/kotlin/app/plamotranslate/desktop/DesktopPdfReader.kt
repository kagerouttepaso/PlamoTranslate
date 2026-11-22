package app.plamotranslate.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import app.plamotranslate.desktop.pdf.PdfDocument
import app.plamotranslate.desktop.pdf.PdfFinished
import app.plamotranslate.desktop.pdf.PdfFonts
import app.plamotranslate.desktop.pdf.PdfSnapshot
import app.plamotranslate.pipeline.pdf.PdfEffect
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfRead
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.image.BufferedImage
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

internal data class PdfPageImage(val bitmap: ImageBitmap, val width: Float, val height: Float)

internal interface PdfReader {
    val events: Flow<PdfEvent>
    val documents: StateFlow<PdfDocuments>
    fun read(request: PdfRead)
    fun write(request: PdfEffect.WritePage)
    fun flush()
    suspend fun save(path: Path, titles: Map<String, String>): PdfFinished
}

internal class DesktopPdfReader(
    private val scope: CoroutineScope,
    private val clock: TimeSource = TimeSource.Monotonic,
) : PdfReader {
    private sealed interface Command {
        data class Read(val request: PdfRead) : Command
        data class Write(val request: PdfEffect.WritePage) : Command
        data class Publish(val document: Long, val force: Boolean) : Command
        data class Capture(val document: Long, val response: CompletableDeferred<PdfSnapshot>) : Command
        data class FontProgress(val done: Long, val total: Long) : Command
        data class FontsLoaded(val result: Result<PdfFonts>) : Command
    }

    private class OpenPdf(
        val id: Long,
        val source: PdfDocument,
        val original: PdfViewVersion,
        val writer: PdfDocument,
    ) {
        val waitingForFonts = mutableListOf<PdfEffect.WritePage>()
        val pendingPages = linkedSetOf<Int>()
        var publishTimer: Job? = null
        var publishedAt: TimeMark? = null
        var saveTime = Duration.ZERO
    }

    private val commands = Channel<Command>(Channel.UNLIMITED, onUndeliveredElement = { command ->
        if (command is Command.Capture) command.response.cancel()
    })
    private val results = Channel<PdfEvent>(Channel.UNLIMITED)
    override val events = results.receiveAsFlow()
    private val mutableDocuments = MutableStateFlow(PdfDocuments())
    override val documents = mutableDocuments.asStateFlow()
    private var active: OpenPdf? = null
    private var versionNumber = 0L

    init {
        scope.launch(Dispatchers.IO) {
            try {
                for (command in commands) {
                    when (command) {
                        is Command.Read -> when (val request = command.request) {
                            is PdfRead.Open -> open(request)
                            is PdfRead.Extract -> extract(request)
                        }
                        is Command.Write -> writePage(command.request)
                        is Command.Publish -> active?.takeIf { it.id == command.document }?.let {
                            it.publishTimer?.cancel()
                            it.publishTimer = null
                            if (command.force) publish(it) else schedulePublication(it)
                        }
                        is Command.Capture -> capture(command)
                        is Command.FontProgress -> setFonts(PdfFontStatus.Loading(command.done, command.total))
                        is Command.FontsLoaded -> fontsLoaded(command.result)
                    }
                }
            } finally {
                closeActive()
                commands.cancel()
                results.close()
            }
        }
    }

    override fun read(request: PdfRead) {
        commands.trySend(Command.Read(request))
    }

    override fun write(request: PdfEffect.WritePage) {
        commands.trySend(Command.Write(request))
    }

    override fun flush() {
        documents.value.documentId?.let { commands.trySend(Command.Publish(it, force = true)) }
    }

    override suspend fun save(path: Path, titles: Map<String, String>): PdfFinished {
        val document = checkNotNull(documents.value.documentId) { "PDF を開いてください" }
        val response = CompletableDeferred<PdfSnapshot>()
        check(commands.trySend(Command.Capture(document, response)).isSuccess) { "PDF を保存できません" }
        val snapshot = try {
            response.await()
        } finally {
            response.cancel()
        }
        val finished = snapshot.finish(titles)
        withContext(Dispatchers.IO) { writeFile(path, finished.bytes) }
        return finished
    }

    private suspend fun capture(command: Command.Capture) {
        if (!command.response.isActive) return
        try {
            val result = pdfAttempt {
                val current = checkNotNull(active?.takeIf { it.id == command.document }) { "PDF を開いてください" }
                current.writer.snapshot()
            }
            result.fold(command.response::complete, command.response::completeExceptionally)
        } finally {
            if (!command.response.isCompleted) command.response.cancel()
        }
    }

    private suspend fun open(request: PdfRead.Open) {
        closeActive()
        mutableDocuments.value = PdfDocuments(documentId = request.document, fonts = documents.value.fonts)
        val result = pdfAttempt {
            val bytes = Files.readAllBytes(Path.of(request.path))
            val source = PdfDocument.open(bytes)
            try {
                check(source.pageCount > 0) { "PDF にページがありません" }
                val writer = PdfDocument.open(bytes)
                OpenPdf(request.document, source, version(source), writer)
            } catch (failure: Throwable) {
                source.close()
                throw failure
            }
        }
        val opened = result.getOrNull()
        active = opened
        val state = documents.value
        mutableDocuments.value = state.copy(original = opened?.original,
            error = result.exceptionOrNull()?.pdfMessage("PDF を開けません"))
        results.send(PdfEvent.Opened(request, opened?.source?.pageCount ?: 0))
    }

    private suspend fun extract(request: PdfRead.Extract) {
        val current = active?.takeIf { it.id == request.document } ?: return
        val page = pdfAttempt { current.source.pageText(request.page) }
        results.send(PdfEvent.Extracted(request, page.getOrNull()))
    }

    private suspend fun writePage(request: PdfEffect.WritePage) {
        val current = active?.takeIf { it.id == request.document } ?: return
        val fonts = (documents.value.fonts as? PdfFontStatus.Ready)?.fonts
        if (fonts == null) {
            current.waitingForFonts += request
            loadFonts()
            return
        }
        val result = pdfAttempt {
            val masked = request.paragraphs.filter { paragraph ->
                paragraph.text in request.translations && paragraph.objectPaths.any { it.size > 1 }
            }
            val backgrounds = current.source.backgroundColors(request.page, masked)
            current.writer.writePage(request.page, request.paragraphs, request.translations, fonts, backgrounds)
        }
        val state = documents.value
        mutableDocuments.value = state.copy(error = result.exceptionOrNull()?.pdfMessage("PDF にページを書き込めません"))
        if ((result.getOrNull()?.translated ?: 0) > 0) current.pendingPages += request.page
        results.send(PdfEvent.Written(request.document, request.page, result.isSuccess))
        schedulePublication(current)
    }

    private fun loadFonts() {
        if (documents.value.fonts is PdfFontStatus.Loading) return
        setFonts(PdfFontStatus.Loading(0, 0))
        scope.launch {
            val result = pdfAttempt { PdfFonts.load(onProgress = { done, total ->
                commands.trySend(Command.FontProgress(done, total))
            }) }
            commands.trySend(Command.FontsLoaded(result))
        }
    }

    private suspend fun fontsLoaded(result: Result<PdfFonts>) {
        val waiting = active?.waitingForFonts
        val requests = waiting?.toList().orEmpty()
        waiting?.clear()
        val fonts = result.getOrNull()
        if (fonts != null) {
            setFonts(PdfFontStatus.Ready(fonts))
            // すでに到着した文書の変更を先に処理し、古い書き込みは文書番号で棄却する。
            requests.forEach(::write)
        } else {
            val message = result.exceptionOrNull()?.pdfMessage("PDF 用フォントを取得できません")
                ?: "PDF 用フォントを取得できません"
            setFonts(PdfFontStatus.Failed(message))
            for (request in requests) results.send(PdfEvent.Written(request.document, request.page, false))
        }
    }

    private fun setFonts(status: PdfFontStatus) {
        mutableDocuments.value = documents.value.copy(fonts = status)
    }

    private suspend fun schedulePublication(current: OpenPdf) {
        if (current.pendingPages.isEmpty()) return
        val minimum = maxOf(1500.milliseconds, current.saveTime * 4)
        val remaining = current.publishedAt?.let { minimum - it.elapsedNow() } ?: Duration.ZERO
        if (remaining <= Duration.ZERO) {
            publish(current)
        } else if (current.publishTimer == null) {
            current.publishTimer = scope.launch {
                delay(remaining)
                commands.trySend(Command.Publish(current.id, force = false))
            }
        }
    }

    private suspend fun publish(current: OpenPdf) {
        if (current.pendingPages.isEmpty()) return
        current.publishTimer?.cancel()
        current.publishTimer = null
        val prepared = pdfAttempt {
            val start = clock.markNow()
            val snapshot = current.writer.snapshot()
            current.saveTime = start.elapsedNow()
            version(PdfDocument.open(snapshot.bytes))
        }
        val state = documents.value
        val next = prepared.getOrNull()
        if (next == null) {
            mutableDocuments.value = state.copy(error = prepared.exceptionOrNull()?.pdfMessage("訳した PDF を表示できません"))
            return
        }
        mutableDocuments.value = state.copy(latest = next,
            writtenPages = state.writtenPages + current.pendingPages, error = null)
        current.pendingPages.clear()
        current.publishedAt = clock.markNow()
        state.latest?.retire()
    }

    private fun version(document: PdfDocument) = PdfViewVersion(++versionNumber, document) { unused ->
        // 画面の破棄と同時にスコープが消えても、文書の close は完了させる。
        scope.launch(start = CoroutineStart.UNDISPATCHED) { unused.close() }
    }

    private suspend fun closeActive() {
        val current = active ?: return
        active = null
        current.publishTimer?.cancel()
        val state = documents.value
        mutableDocuments.value = PdfDocuments(fonts = state.fonts)
        state.latest?.retire()
        current.original.retire()
        current.writer.close()
    }
}

private inline fun <T> pdfAttempt(operation: () -> T): Result<T> = try {
    Result.success(operation())
} catch (failure: CancellationException) {
    throw failure
} catch (failure: Exception) {
    Result.failure(failure)
} catch (failure: LinkageError) {
    Result.failure(failure)
}

private fun Throwable.pdfMessage(fallback: String) = message?.takeIf(String::isNotBlank) ?: fallback

private fun writeFile(path: Path, bytes: ByteArray) {
    val destination = path.toAbsolutePath().normalize()
    val temporary = Files.createTempFile(destination.parent, ".mockingword-", ".pdf")
    try {
        Files.write(temporary, bytes)
        try {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

internal suspend fun renderPdf(document: PdfDocument, page: Int, zoom: Int): PdfPageImage = withContext(Dispatchers.Default) {
    val size = document.pageSize(page)
    val scale = minOf(zoom / 100.0 * 1.5, 4096 / maxOf(size.width, size.height), sqrt(5_000_000 / size.width / size.height))
    val width = (size.width * scale).roundToInt().coerceIn(1, 4096)
    val height = (size.height * scale).roundToInt().coerceIn(1, 4096)
    val pixels = document.render(page, width, height)
    val bitmap = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    bitmap.setRGB(0, 0, width, height, pixels, 0, width)
    PdfPageImage(bitmap.toComposeImageBitmap(), size.width.toFloat(), size.height.toFloat())
}
