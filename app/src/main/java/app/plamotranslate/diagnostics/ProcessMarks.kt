package app.plamotranslate.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.util.Log
import app.plamotranslate.pipeline.diagnostics.ProcessMark

/**
 * プロセスの印を OS に預ける（外とつなぐ部品。中核の [ProcessMark]）。印は次の起動で OS の終わり方の記録から戻り、
 * 同じプロセスの記録を見分けるのと、アプリが意図して終えたかを知るのに使う（不具合の記録と `GpuGuard`）。
 */
object ProcessMarks {
    private const val TAG = "PlamoTranslate"

    /** このプロセスの印。預けられなかったら null。 */
    @Volatile
    var current: ProcessMark? = null
        private set

    /** プロセスが始まるときに1度呼ぶ。 */
    fun begin(context: Context) {
        val mark = ProcessMark.create()
        if (put(context, mark)) current = mark
    }

    /**
     * アプリが意図してプロセスを終える直前に呼ぶ（設定を変えて起動し直すとき）。次の起動で、この終わり方を
     * 落ちたとみなさない（不具合として記録せず、GPU の読み込み中だったとしても GPU を外さない）。
     */
    fun markIntendedExit(context: Context) {
        current?.let { put(context, it.copy(intended = true)) }
    }

    private fun put(context: Context, mark: ProcessMark): Boolean = runCatching {
        context.getSystemService(ActivityManager::class.java).setProcessStateSummary(mark.encode())
    }.onFailure { Log.w(TAG, "プロセスの印を預けられませんでした", it) }.isSuccess
}
