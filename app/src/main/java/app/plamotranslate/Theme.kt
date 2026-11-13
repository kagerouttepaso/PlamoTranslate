package app.plamotranslate

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/*
 * アプリの色（docs/設計/デザイン言語.md「色」）。ダークは「夜の鳥」（紺の地にコーラルとミント）、
 * ライトは「さえずりの紙」（生成りの紙と墨に、朱と緑）。2つのテーマは色の役割をそろえる：
 * - primary：自分・話すこと・主な操作（夜の鳥はコーラル、さえずりの紙は朱）
 * - secondary：相手と訳（夜の鳥はミント、さえずりの紙は緑）
 * - tertiary：画面を読む機能（夜の鳥は空色、さえずりの紙は藍）
 * - surface：地と、その上の面の段
 * 夜の鳥は色を「光」として使う。広い面（*Container を含む）は紺の段に役割の色を少し差したものにし、
 * コーラル・ミント・空色は明るいまま、アイコン・縁・文字と、画面の主役の1つだけに使う。
 * 色を暗くしただけの面（茶・くすんだ緑）は濁って見えるので作らない（docs/設計/デザイン言語.md「色」）。
 */

/** 夜の鳥（ダーク）。 */
private val NightBird: ColorScheme = darkColorScheme(
    primary = Color(0xFFFF8A65),
    onPrimary = Color(0xFF3B1408),
    primaryContainer = Color(0xFF6A2F42),
    onPrimaryContainer = Color(0xFFFFDCD0),
    secondary = Color(0xFF7FE0C8),
    onSecondary = Color(0xFF003829),
    secondaryContainer = Color(0xFF185867),
    onSecondaryContainer = Color(0xFFC6F6EA),
    tertiary = Color(0xFFA9B8FF),
    onTertiary = Color(0xFF15226A),
    tertiaryContainer = Color(0xFF2F3B7E),
    onTertiaryContainer = Color(0xFFDDE1FF),
    background = Color(0xFF141A30),
    onBackground = Color(0xFFE8EAF6),
    surface = Color(0xFF141A30),
    onSurface = Color(0xFFE8EAF6),
    surfaceVariant = Color(0xFF2C3458),
    onSurfaceVariant = Color(0xFFB9BEDA),
    surfaceContainerLowest = Color(0xFF0F1426),
    surfaceContainerLow = Color(0xFF181F38),
    surfaceContainer = Color(0xFF1B2240),
    surfaceContainerHigh = Color(0xFF20284A),
    surfaceContainerHighest = Color(0xFF2C3458),
    outline = Color(0xFF5A6390),
    outlineVariant = Color(0xFF2C3458),
)

/** さえずりの紙（ライト）。 */
private val PaperChirp: ColorScheme = lightColorScheme(
    primary = Color(0xFFC23B22),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFFDAD2),
    onPrimaryContainer = Color(0xFF5C1305),
    secondary = Color(0xFF3E6B5A),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFC9E8DA),
    onSecondaryContainer = Color(0xFF0F2C22),
    tertiary = Color(0xFF4A5A8C),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFDDE2F7),
    onTertiaryContainer = Color(0xFF17213F),
    background = Color(0xFFFBF7F0),
    onBackground = Color(0xFF2A2622),
    surface = Color(0xFFFBF7F0),
    onSurface = Color(0xFF2A2622),
    surfaceVariant = Color(0xFFEFE6D8),
    onSurfaceVariant = Color(0xFF6B6156),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFF7F1E7),
    surfaceContainer = Color(0xFFF4ECDF),
    surfaceContainerHigh = Color(0xFFEFE6D8),
    surfaceContainerHighest = Color(0xFFE8DDCB),
    outline = Color(0xFF9C9083),
    outlineVariant = Color(0xFFE5DCCB),
)

/** 画面の色の選び方（設定の「画面の色」）。 */
enum class ThemeMode(val label: String) { SYSTEM("端末に合わせる"), LIGHT("ライト（さえずりの紙）"), DARK("ダーク（夜の鳥）") }

/** 画面の色の設定。変えたら開いている画面にもすぐ効くよう、流れで持つ。 */
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
}

/**
 * アプリ共通の色。すべての画面でこれを使う。設定の「画面の色」が端末に合わせるなら端末のダーク設定に従う。
 * 状態バーとナビゲーションバーのアイコンの明暗も、アプリの明暗に合わせる（端末がダークでアプリがライトのときに見えなくならないように）。
 */
@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val mode by ThemePreferences.mode.collectAsState()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) NightBird else PaperChirp, content = content)
}
