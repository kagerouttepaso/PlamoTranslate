package app.plamotranslate.diagnostics

import android.app.ActivityManager
import android.content.Context
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.diagnostics.ProcessMark

/**
 * プロセスのマークを OS に預ける（アダプター。中核の [ProcessMark]）。マークは次の起動で OS の終了理由の記録から戻り、
 * 同じプロセスの記録を見分けるのと、アプリが意図して終えたかを知るのに使う（不具合の記録と `GpuGuard`）。
 */
object ProcessMarks {
    /** このプロセスのマーク。預けられなかったら null。 */
    @Volatile
    var current: ProcessMark? = null
        private set

    /** プロセスが始まるときに1度呼ぶ。 */
    fun begin(context: Context) {
        val mark = ProcessMark.create()
        if (put(context, mark)) current = mark
    }

    /**
     * アプリが意図してプロセスを終える直前に呼ぶ（設定を変えて起動し直すとき）。次の起動で、この終了理由を
     * 落ちたとみなさない（不具合として記録せず、GPU の読み込み中だったとしても GPU を外さない）。
     */
    fun markIntendedExit(context: Context) {
        current?.let { put(context, it.copy(intended = true)) }
    }

    private fun put(context: Context, mark: ProcessMark): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).setProcessStateSummary(mark.encode())
    }.onFailure { Logs.w("プロセスのマークを預けられませんでした", it) }.isSuccess
}
