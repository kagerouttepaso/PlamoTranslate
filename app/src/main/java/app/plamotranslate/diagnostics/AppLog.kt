package app.plamotranslate.diagnostics

import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 端末のログ（logcat）の写し（外とつなぐ部品）。アプリが読めるのは自分（同じ UID）が出した行だけで、前のプロセスの行も読める
 * （featurelab で確かめた）。落ちたときの例外（AndroidRuntime の FATAL EXCEPTION）は crash のバッファーにあるので、それも読む。
 *
 * 端末によってはアプリのログが書かれない。REDMAGIC は既定で `log.tag=S` で、開けていなければ写しはほぼ空になる
 * （docs/資料/端末の能力.md「REDMAGICはログを黙らせている」）。
 */
object AppLog {
    /** 写す行数。落ちる前の数分ぶんを目安にする。 */
    private const val LINES = 4000

    /** 読み終えるまで待つ時間。logcat が止まっても、呼んだスレッドを止めたままにしない。 */
    private const val TIMEOUT_SECONDS = 10L

    fun snapshot(): String {
        val process = runCatching {
            ProcessBuilder("logcat", "-d", "-b", "main,system,crash", "-v", "threadtime", "-t", LINES.toString())
                .redirectErrorStream(true)
                .start()
        }.getOrElse { return "ログを読めませんでした：${it.message}" }
        val reading = FutureTask { process.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() } }
        Thread(reading, "AppLog").apply { isDaemon = true }.start()
        return try {
            reading.get(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        } catch (e: TimeoutException) {
            "ログの読み出しが${TIMEOUT_SECONDS}秒で終わりませんでした"
        } catch (e: Exception) {
            "ログを読めませんでした：${e.message}"
        } finally {
            process.destroy()
        }
    }
}
