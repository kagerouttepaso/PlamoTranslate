package app.plamotranslate.overlay

import android.content.Context
import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.toArgb
import app.plamotranslate.NightBird
import app.plamotranslate.PaperChirp
import app.plamotranslate.ThemePreferences

/**
 * ほかのアプリの画面の上に出す部品（画面翻訳のフローティングボタン・メニュー・ステータス表示）の色（docs/設計/デザイン言語.md「色」）。
 * Android View で描くので MaterialTheme を使えないが、値はアプリのテーマ（夜の鳥・さえずりの紙。Theme.kt）の色の定数から作り、
 * ダークで描くかの判定も同じもの（[ThemePreferences.dark]）を使う。下の画面の明るさによらず見分けられるよう、面は少しだけ透かし、
 * フローティングボタンにはプライマリカラーの縁を付ける。
 */
class OverlayPalette private constructor(
    /** 面。 */
    val surface: Int,
    /** フローティングボタンとステータス表示の面。 */
    val surfaceRaised: Int,
    /** プライマリカラー（コーラル・朱）と、その上の文字。 */
    val primary: Int,
    val onPrimary: Int,
    /** 面の上の文字と、控えめな文字・枠。 */
    val onSurface: Int,
    val onSurfaceMuted: Int,
    val outline: Int,
) {
    companion object {
        /** テーマの色から作る。[surfaceAlpha]・[raisedAlpha] は面の透かし（ライトは地が明るいので少し濃くする）。 */
        private fun of(scheme: ColorScheme, surfaceAlpha: Float, raisedAlpha: Float) = OverlayPalette(
            surface = scheme.surface.copy(alpha = surfaceAlpha).toArgb(),
            surfaceRaised = scheme.surfaceVariant.copy(alpha = raisedAlpha).toArgb(),
            primary = scheme.primary.toArgb(),
            onPrimary = scheme.onPrimary.toArgb(),
            onSurface = scheme.onSurface.toArgb(),
            onSurfaceMuted = scheme.onSurfaceVariant.toArgb(),
            outline = scheme.outline.toArgb(),
        )

        /** 夜の鳥（ダーク）。 */
        private val NIGHT = of(NightBird, surfaceAlpha = 240 / 255f, raisedAlpha = 235 / 255f)

        /** さえずりの紙（ライト）。 */
        private val PAPER = of(PaperChirp, surfaceAlpha = 245 / 255f, raisedAlpha = 240 / 255f)

        /** 今のテーマの色。設定の「画面の色」が端末に合わせるなら、[context] の明暗に従う。 */
        fun current(context: Context): OverlayPalette =
            if (ThemePreferences.dark(ThemePreferences.mode.value, ThemePreferences.systemDark(context.resources.configuration))) NIGHT else PAPER
    }
}
