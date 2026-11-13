package app.plamotranslate.diagnostics

import android.content.Context

/** 不具合の記録の設定と控え（SharedPreferences の `diagnostics`）。 */
class DiagnosticsPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("diagnostics", Context.MODE_PRIVATE)

    /**
     * OS の終わり方の記録を、どの時刻まで取り込んだか。記録を消しても戻さない（同じ終わり方を取り込み直さない）。
     */
    var exitsReadUntil: Long
        get() = prefs.getLong("exits_read_until", 0L)
        set(value) = prefs.edit().putLong("exits_read_until", value).apply()
}
