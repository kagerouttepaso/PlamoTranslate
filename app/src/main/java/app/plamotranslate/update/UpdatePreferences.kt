package app.plamotranslate.update

import android.content.Context

/** 更新の設定（SharedPreferences の `update`）。 */
class UpdatePreferences(context: Context) {
    private val prefs = context.getSharedPreferences("update", Context.MODE_PRIVATE)

    /**
     * ベータ（GitHub の pre-release）も受け取るか。既定は正式版だけ。
     * 外しても、入っているベータより新しい正式版が出るまでは、そのベータのまま（版を下げることはしない）。
     */
    var includeBeta: Boolean
        get() = prefs.getBoolean("includeBeta", false)
        set(value) = prefs.edit().putBoolean("includeBeta", value).apply()
}
