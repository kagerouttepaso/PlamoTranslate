package app.plamotranslate.pipeline.diagnostics

import java.util.UUID

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

    /** シグナルで止められた（[ProcessExit.status] はシグナルの番号）。自分で `killProcess` したときもこれになる。 */
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

/** 終わったときのプロセスの重要度（OS の重要度を、使っている最中かの判断に要る分だけに分けたもの。上ほど本人に近い）。 */
enum class ProcessImportance {
    /** 前面の画面。 */
    FOREGROUND,

    /** フォアグラウンドサービス（画面翻訳）。 */
    FOREGROUND_SERVICE,

    /** 見えているが前面ではない。 */
    VISIBLE,

    /** 本人が気づく処理（音を出している など）。 */
    PERCEPTIBLE,

    /** 裏に回っている。 */
    BACKGROUND,
}

/**
 * プロセスに付ける印。起動のたびに新しい [id] を作って OS に預け（`setProcessStateSummary`）、次の起動で OS の終わり方の記録から
 * 受け取る。同じプロセスの記録を見分けるのと、アプリが意図して終えたか（[intended]）を知るのに使う。
 * どの終わり方でも OS の記録に戻ることを確かめてある（docs/資料/端末の能力.md「プロセスの終わり方」）。
 */
data class ProcessMark(val id: String, val intended: Boolean = false) {
    /** OS に預ける形（128バイトまで）。 */
    fun encode(): ByteArray = (PREFIX + id + if (intended) INTENDED else "").toByteArray()

    companion object {
        private const val PREFIX = "mw1:"
        private const val INTENDED = ":intended"

        fun create(): ProcessMark = ProcessMark(UUID.randomUUID().toString().replace("-", "").take(16))

        /** OS から戻った形を読む。このアプリの印でなければ null。 */
        fun decode(bytes: ByteArray?): ProcessMark? {
            val text = bytes?.decodeToString()?.takeIf { it.startsWith(PREFIX) } ?: return null
            val body = text.removePrefix(PREFIX)
            return ProcessMark(body.removeSuffix(INTENDED), intended = body.endsWith(INTENDED))
        }
    }
}

/**
 * プロセスの終わり方の1件。
 *
 * @property time 終わった時刻（エポックからのミリ秒）
 * @property status [ExitCause.EXIT_SELF] なら終了コード、[ExitCause.SIGNALED] ならシグナルの番号
 * @property mark そのプロセスに付けた印。印を付ける前の版の記録や、OS が印を残さなかった記録では null
 * @property rawReason OS の理由の値（[ExitCause.OTHER] の説明に使う）
 * @property description OS が添えた説明
 */
data class ProcessExit(
    val time: Long,
    val pid: Int,
    val cause: ExitCause,
    val status: Int = 0,
    val importance: ProcessImportance = ProcessImportance.BACKGROUND,
    val mark: ProcessMark? = null,
    val rawReason: Int = 0,
    val description: String = "",
) {
    /** 同じプロセスの記録を見分ける鍵（[keyOf]）。 */
    val processKey: String get() = keyOf(mark, pid)

    companion object {
        /** プロセスの鍵。印があれば印、無ければ pid。記録とログのファイルで同じ鍵を使う。 */
        fun keyOf(mark: ProcessMark?, pid: Int): String = mark?.id ?: "pid$pid"
    }
}

/**
 * 終わり方の判定。GPU を外すか（`GpuGuard`）と、不具合として記録するか（不具合の記録）で同じ判定を使う。
 */
object ExitJudge {
    /** 強制停止や OS による終了で送られるシグナル。 */
    private const val SIGKILL = 9

    /** アプリが意図して終えたか（設定を変えて起動し直したとき など）。 */
    fun intended(exit: ProcessExit): Boolean = exit.mark?.intended == true

    /**
     * 異常終了か。例外・ネイティブの落ち方・応答なし・起動の失敗に加え、終了コードが0でない自分での終了
     * （llama.cpp の OpenCL はカーネルの組み立てに失敗すると `exit(1)` する）と、SIGKILL 以外のシグナルも異常とみなす。
     * アプリが意図して終えたものは異常としない。
     */
    fun abnormal(exit: ProcessExit): Boolean = !intended(exit) && when (exit.cause) {
        ExitCause.EXCEPTION, ExitCause.NATIVE_CRASH, ExitCause.NOT_RESPONDING, ExitCause.INIT_FAILURE -> true
        ExitCause.EXIT_SELF -> exit.status != 0
        ExitCause.SIGNALED -> exit.status != SIGKILL
        else -> false
    }

    /** 終わったとき本人が使っていたか。前面の画面・フォアグラウンドサービス（画面翻訳）・本人が気づく処理なら、使っていた。 */
    fun inUse(exit: ProcessExit): Boolean = exit.importance <= ProcessImportance.PERCEPTIBLE

    /**
     * 不具合として記録するか。異常終了に加え、本人が使っている最中に OS に止められたもの（メモリ不足・資源の使いすぎ・SIGKILL）も、
     * 本人には「落ちた」と見えるので記録する。裏に回った後に OS が片付けた終わり方と、アプリが意図して終えたものは記録しない。
     */
    fun recordable(exit: ProcessExit): Boolean = !intended(exit) && (
        abnormal(exit) ||
            (inUse(exit) && exit.cause in setOf(ExitCause.LOW_MEMORY, ExitCause.EXCESSIVE_RESOURCE, ExitCause.SIGNALED))
        )

    /** 同じプロセスの記録か。印が両方にあれば印で、どちらかに無ければ pid で見る（1つのプロセスは1度しか終わらない）。 */
    fun sameProcess(a: ProcessExit, b: ProcessExit): Boolean =
        if (a.mark != null && b.mark != null) a.mark.id == b.mark.id else a.pid == b.pid
}

/** 終わり方の説明の文（画面と記録に出す）。 */
object ExitTexts {
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

/**
 * OS の終わり方の記録のうち、何を取り込むかの計画（[plan]）。
 *
 * @property record 記録する終わり方（古い順）
 * @property readUntil 次に取り込むときの目印（ここまでの時刻を読んだ）
 */
class ExitSelection<T>(val record: List<T>, val readUntil: Long) {
    companion object {
        /**
         * [items]（OS の記録。[exitOf] で終わり方に読み替える）から、[readUntil] より後のものを読む。
         * 同じプロセスの記録が2件あれば1件にする（例外で落ちると、OS は受け口の直後と、「停止しました」を閉じた後の2件を残す。
         * 印のある先のほうを採る）。そのうえで記録するもの（[ExitJudge.recordable]）を選ぶ。
         */
        fun <T> plan(items: List<T>, exitOf: (T) -> ProcessExit, readUntil: Long): ExitSelection<T> {
            val fresh = items.filter { exitOf(it).time > readUntil }
            val unique = mutableListOf<T>()
            for (item in fresh.sortedWith(compareBy({ exitOf(it).mark == null }, { exitOf(it).time }))) {
                if (unique.none { ExitJudge.sameProcess(exitOf(it), exitOf(item)) }) unique += item
            }
            val record = unique.filter { ExitJudge.recordable(exitOf(it)) }.sortedBy { exitOf(it).time }
            return ExitSelection(
                record = record,
                readUntil = maxOf(readUntil, fresh.maxOfOrNull { exitOf(it).time } ?: readUntil),
            )
        }
    }
}
