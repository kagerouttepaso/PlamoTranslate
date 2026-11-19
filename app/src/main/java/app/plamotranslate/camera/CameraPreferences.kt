package app.plamotranslate.camera

import android.content.Context
import app.plamotranslate.llm.Language
import app.plamotranslate.overlay.storedCode
import app.plamotranslate.overlay.storedSource

/**
 * カメラ翻訳の設定（原文の言語・訳す先・表のモード）。端末に残し、次に開いたときも引き継ぐ
 * （`docs/判断/2026-11-09_設定の保存は機能ごとのPreferencesにまとめDataStoreへ移さない.md`）。
 */
internal class CameraPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("camera_translate", Context.MODE_PRIVATE)

    /**
     * 原文の言語。null は自動。
     * 以前は認識器の種類（OcrLanguage）を "language" に保存していたので、新しい値が無ければそれを読み替える。
     */
    var source: Language?
        get() = storedSource(prefs.getString(KEY_SOURCE, null), prefs.getString(KEY_LEGACY_SOURCE, null), CAMERA_SOURCES)
        set(language) = prefs.edit().putString(KEY_SOURCE, language.storedCode).apply()

    /** 訳す先。既定は日本語。 */
    var target: Language
        get() = Language.ofCode(prefs.getString(KEY_TARGET, null).orEmpty()) ?: Language.JA
        set(language) = prefs.edit().putString(KEY_TARGET, language.code).apply()

    /** 表のモード（1行ずつ訳す）か。文章のモードは行を段落にまとめて訳す。 */
    var tableMode: Boolean
        get() = prefs.getBoolean(KEY_TABLE, false)
        set(table) = prefs.edit().putBoolean(KEY_TABLE, table).apply()

    private companion object {
        const val KEY_SOURCE = "source"
        const val KEY_LEGACY_SOURCE = "language"
        const val KEY_TARGET = "target"
        const val KEY_TABLE = "table"
    }
}
