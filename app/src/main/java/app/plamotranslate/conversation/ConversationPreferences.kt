package app.plamotranslate.conversation

import android.content.Context
import app.plamotranslate.llm.Language

/** 会話翻訳の設定（表示モード・自分の言語）。次に開いたときも同じにする。 */
internal class ConversationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation", Context.MODE_PRIVATE)

    /** 表示モード。保存が無ければ null（画面の既定を使う）。 */
    var viewMode: ViewMode?
        get() = prefs.getString(KEY_VIEW_MODE, null)?.let { name -> ViewMode.entries.firstOrNull { it.name == name } }
        set(mode) = prefs.edit().putString(KEY_VIEW_MODE, mode?.name).apply()

    /** 自分の言語。保存が無い・[languages]（選んだ音声認識モデルの言語）に無いなら null。 */
    fun self(languages: List<Language>): Language? =
        Language.ofCode(prefs.getString(KEY_SELF, null).orEmpty())?.takeIf { it in languages }

    fun saveSelf(language: Language) = prefs.edit().putString(KEY_SELF, language.code).apply()

    private companion object {
        const val KEY_VIEW_MODE = "view_mode"
        const val KEY_SELF = "self"
    }
}
