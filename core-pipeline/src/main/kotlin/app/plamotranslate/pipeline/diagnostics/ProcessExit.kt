package app.plamotranslate.pipeline.diagnostics

/**
 * プロセスの終わり方の原因。OS が残す前回の終わり方（Android の `ApplicationExitInfo` の理由）を、判断に要る分だけに分けたもの。
 * OS の値からの読み替えは `app` の外とつなぐ部品（`diagnostics/ProcessExits.kt`）が行う。
 */
enum class ExitCause {
    /** Java・Kotlin の例外で落ちた。 */
    EXCEPTION,

    /** ネイティブのライブラリ（llama.cpp・ONNX Runtime など）で落ちた。 */
    NATIVE_CRASH,

    /** 応答なし（ANR）で止められた。 */
    NOT_RESPONDING,

    /** 起動の途中で失敗した。 */
    INIT_FAILURE,

    /** プロセスが自分で終わった（[ProcessExit.status] は終了コード）。 */
    EXIT_SELF,

    /** シグナルで止められた（[ProcessExit.status] はシグナルの番号）。 */
    SIGNALED,

    /** メモリ不足で OS に止められた。 */
    LOW_MEMORY,

    /** 資源（CPU など）の使いすぎで OS に止められた。 */
    EXCESSIVE_RESOURCE,

    /** 本人が強制停止した。 */
    USER_STOPPED,

    /** そのほか（更新・権限の変更・本人が閉じた など）。 */
    OTHER,
}

/**
 * プロセスの終わり方の1件。
 *
 * @property time 終わった時刻（エポックからのミリ秒）
 * @property status [ExitCause.EXIT_SELF] なら終了コード、[ExitCause.SIGNALED] ならシグナルの番号
 * @property inUse 終わったとき本人が使っていたか（前面の画面、フォアグラウンドサービスなど、本人が気づく状態）
 * @property rawReason OS の理由の値（[ExitCause.OTHER] の説明に使う）
 * @property description OS が添えた説明
 */
data class ProcessExit(
    val time: Long,
    val pid: Int,
    val cause: ExitCause,
    val status: Int = 0,
    val inUse: Boolean = false,
    val rawReason: Int = 0,
    val description: String = "",
)

/**
 * 終わり方の判定。GPU を外すか（`GpuGuard`）と、不具合として記録するか（不具合の記録）で同じ判定を使う。
 */
object ExitJudge {
    /** 強制停止や OS による終了で送られるシグナル。 */
    private const val SIGKILL = 9

    /**
     * 異常終了か。例外・ネイティブの落ち方・応答なし・起動の失敗に加え、終了コードが0でない自分での終了
     * （llama.cpp の OpenCL はカーネルの組み立てに失敗すると `exit(1)` する）と、SIGKILL 以外のシグナルも異常とみなす。
     */
    fun abnormal(exit: ProcessExit): Boolean = when (exit.cause) {
        ExitCause.EXCEPTION, ExitCause.NATIVE_CRASH, ExitCause.NOT_RESPONDING, ExitCause.INIT_FAILURE -> true
        ExitCause.EXIT_SELF -> exit.status != 0
        ExitCause.SIGNALED -> exit.status != SIGKILL
        else -> false
    }

    /** 画面と記録に出す終わり方の説明。 */
    fun label(exit: ProcessExit): String = when (exit.cause) {
        ExitCause.EXCEPTION -> "例外で落ちた"
        ExitCause.NATIVE_CRASH -> "ネイティブのライブラリで落ちた"
        ExitCause.NOT_RESPONDING -> "応答なし（ANR）"
        ExitCause.INIT_FAILURE -> "起動の途中で失敗した"
        ExitCause.EXIT_SELF -> "プロセスが終了コード${exit.status}で終わった"
        ExitCause.SIGNALED -> "シグナル${exit.status}で止められた"
        ExitCause.LOW_MEMORY -> "メモリ不足でOSに止められた"
        ExitCause.EXCESSIVE_RESOURCE -> "資源の使いすぎでOSに止められた"
        ExitCause.USER_STOPPED -> "強制停止"
        ExitCause.OTHER -> "終了の理由${exit.rawReason}"
    }
}
