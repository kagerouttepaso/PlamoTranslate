package app.plamotranslate.log

/** ログの重さ。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** ログの書き先（差し込み口。実装は app の logcat とアプリのファイル）。 */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * ログの出口（docs/設計/アーキテクチャ.md「ログ」）。どのモジュールのログもここを通す。命令だけで、返り値は無い。
 * 書き先へは呼んだスレッドで1行ずつ渡して戻る（落ちても、渡した行がファイルに残るように）。
 *
 * - 書き先は app が起動時に [install] で差す。差す前（単体テストなど）は、どこにも書かない。
 * - 利用者の文（原文・訳・読み取った文字・話した言葉）を含む行は [text] で渡す。[install] の userText が false
 *   （正式版）なら書かない。書き出した不具合の記録に、利用者の文が入らないようにするため。
 */
object Logs {
    /** アプリのログの名前（logcat のタグ）。 */
    const val TAG = "PlamoTranslate"

    @Volatile
    private var sinks: List<LogSink> = emptyList()

    @Volatile
    private var userText = false

    /** 書き先を差す。プロセスが始まるときに1度呼ぶ。 */
    fun install(sinks: List<LogSink>, userText: Boolean) {
        this.sinks = sinks
        this.userText = userText
    }

    fun d(message: String) = write(LogLevel.DEBUG, message, null)

    fun i(message: String) = write(LogLevel.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = write(LogLevel.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = write(LogLevel.ERROR, message, error)

    /** 利用者の文を含む行。正式版では書かない（作らないよう、文は関数で受け取る）。 */
    fun text(message: () -> String) {
        if (userText) write(LogLevel.DEBUG, message(), null)
    }

    /** 重さを値で渡して書く（ネイティブのログの受け口から）。 */
    fun write(level: LogLevel, message: String, error: Throwable?) {
        for (sink in sinks) {
            // 書き先の失敗で、ログを出した側を止めない
            runCatching { sink.write(level, TAG, message, error) }
        }
    }
}
