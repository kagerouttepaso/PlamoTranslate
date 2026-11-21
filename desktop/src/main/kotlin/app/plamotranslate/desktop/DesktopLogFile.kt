package app.plamotranslate.desktop

import app.plamotranslate.log.LogLevel
import app.plamotranslate.log.LogSink
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.APPEND
import java.nio.file.StandardOpenOption.CREATE
import java.time.Instant

internal class DesktopLogFile(private val directory: Path, private val limit: Long = 256 * 1024) : LogSink {
    private val current = directory.resolve("desktop.log")
    private val previous = directory.resolve("desktop.previous.log")

    @Synchronized
    override fun write(level: LogLevel, tag: String, message: String, error: Throwable?) {
        val reason = error?.let { " (${it.javaClass.simpleName})" }.orEmpty()
        val line = "${Instant.now()} $tag $level: ${message.take(8192)}$reason\n".toByteArray()
        Files.createDirectories(directory)
        if (Files.exists(current) && Files.size(current) + line.size > limit) {
            Files.move(current, previous, REPLACE_EXISTING)
        }
        Files.write(current, line, CREATE, APPEND)
    }
}
