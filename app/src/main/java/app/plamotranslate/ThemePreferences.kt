package app.plamotranslate

import android.content.Context
import android.content.res.Configuration
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 画面の色の選び方（設定の「画面の色」）。 */
enum class ThemeMode(val label: String) { SYSTEM("端末に合わせる"), LIGHT("ライト（さえずりの紙）"), DARK("ダーク（夜の鳥）") }

/**
 * 画面の色の設定。変えたら開いている画面にもすぐ効くよう、`StateFlow` で持つ。ダークで描くかの判定は [dark] の1つだけにし、
 * アプリの画面（[AppTheme]）とほかのアプリの上に出す部品（[app.plamotranslate.overlay.OverlayPalette]）の両方がこれを使う。
 */
object ThemePreferences {
    private const val FILE = "appearance"
    private const val KEY_MODE = "theme"
    private val _mode = MutableStateFlow(ThemeMode.SYSTEM)
    val mode: StateFlow<ThemeMode> = _mode.asStateFlow()

    /** アプリの起動時に一度読む（App.onCreate）。 */
    fun load(context: Context) {
        val saved = context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        _mode.value = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.SYSTEM
    }

    fun set(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(KEY_MODE, mode.name).apply()
        _mode.value = mode
    }

    /** ダークで描くか。端末に合わせるなら、端末のダーク設定（[systemDark]）に従う。 */
    fun dark(mode: ThemeMode, systemDark: Boolean): Boolean = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    /** 端末のダーク設定（[configuration] の明暗）。 */
    fun systemDark(configuration: Configuration): Boolean =
        configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
}
