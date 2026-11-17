package app.plamotranslate.log

/** ログの重さ。 */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** ログの出力先（インターフェース。実装は app の logcat とアプリのファイル）。 */
fun interface LogSink {
    fun write(level: LogLevel, tag: String, message: String, error: Throwable?)
}

/**
 * ロガー（docs/設計/アーキテクチャ.md「ログ」）。どのモジュールのログもここを通す。命令だけで、返り値は無い。
 * 出力先へは呼んだスレッドで1行ずつ渡して戻る（落ちても、渡した行がファイルに残るように）。
 *
 * - 出力先は app が起動時に [install] で差し込む。差す前（単体テストなど）は、どこにも書かない。
 * - 利用者の文（原文・訳・読み取った文字・話した言葉）を含む行は [text] で渡す。userText が false なら書かない。
 *   書き出した不具合の記録に、本人が選ばないかぎり利用者の文が入らないようにするため。
 *   true にするのは、詳細設定の「詳しい記録を残す」がオンのとき（デバッグ版の既定はオン、正式版は本人が選ぶ。[install]・[allowUserText]）。
 */
object Logs {
    /** アプリのログの名前（logcat のタグ）。 */
    const val TAG = "PlamoTranslate"

    @Volatile
    private var sinks: List<LogSink> = emptyList()

    @Volatile
    private var userText = false

    /** 出力先を渡す。プロセスが始まるときに1度呼ぶ。 */
    fun install(sinks: List<LogSink>, userText: Boolean) {
        this.sinks = sinks
        this.userText = userText
    }

    /** 利用者の文を書くかを変える（詳細設定で選び直したとき。出力先はそのまま）。 */
    fun allowUserText(enabled: Boolean) {
        userText = enabled
    }

    fun d(message: String) = write(LogLevel.DEBUG, message, null)

    fun i(message: String) = write(LogLevel.INFO, message, null)

    fun w(message: String, error: Throwable? = null) = write(LogLevel.WARN, message, error)

    fun e(message: String, error: Throwable? = null) = write(LogLevel.ERROR, message, error)

    /** 利用者の文を含む行。許していなければ書かない（作らないよう、文は関数で受け取る）。 */
    fun text(message: () -> String) {
        if (userText) write(LogLevel.DEBUG, message(), null)
    }

    /** 重さを値で渡して書く（ネイティブのログのコールバックから）。 */
    fun write(level: LogLevel, message: String, error: Throwable?) {
        for (sink in sinks) {
            // 出力先の失敗で、ログを出した側を止めない
            runCatching { sink.write(level, TAG, message, error) }
        }
    }
}
