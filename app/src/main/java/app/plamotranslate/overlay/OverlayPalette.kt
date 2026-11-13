package app.plamotranslate.overlay

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import app.plamotranslate.ThemeMode
import app.plamotranslate.ThemePreferences

/**
 * ほかのアプリの画面の上に出す部品（画面翻訳の浮遊ボタン・メニュー・状態の表示）の色（docs/設計/デザイン言語.md「色」）。
 * アプリのテーマ（夜の鳥・さえずりの紙）に合わせる。Android View で描くので MaterialTheme を使えず、値をここに置く
 * （値は Theme.kt の2つのテーマと同じ）。下の画面の明るさによらず見分けられるよう、面は少しだけ透かし、浮遊ボタンには主の色の縁を付ける。
 */
class OverlayPalette private constructor(
    /** 面。 */
    val surface: Int,
    /** 浮遊ボタンと状態の表示の面。 */
    val surfaceRaised: Int,
    /** 主の色（コーラル・朱）と、その上の文字。 */
    val primary: Int,
    val onPrimary: Int,
    /** 面の上の文字と、控えめな文字・枠。 */
    val onSurface: Int,
    val onSurfaceMuted: Int,
    val outline: Int,
) {
    companion object {
        /** 夜の鳥（ダーク）。 */
        private val NIGHT = OverlayPalette(
            surface = Color.argb(240, 0x14, 0x1A, 0x30),
            surfaceRaised = Color.argb(235, 0x2C, 0x34, 0x58),
            primary = Color.rgb(0xFF, 0x8A, 0x65),
            onPrimary = Color.rgb(0x3B, 0x14, 0x08),
            onSurface = Color.rgb(0xE8, 0xEA, 0xF6),
            onSurfaceMuted = Color.rgb(0xB9, 0xBE, 0xDA),
            outline = Color.rgb(0x5A, 0x63, 0x90),
        )

        /** さえずりの紙（ライト）。 */
        private val PAPER = OverlayPalette(
            surface = Color.argb(245, 0xFB, 0xF7, 0xF0),
            surfaceRaised = Color.argb(240, 0xEF, 0xE6, 0xD8),
            primary = Color.rgb(0xC2, 0x3B, 0x22),
            onPrimary = Color.WHITE,
            onSurface = Color.rgb(0x2A, 0x26, 0x22),
            onSurfaceMuted = Color.rgb(0x6B, 0x61, 0x56),
            outline = Color.rgb(0x9C, 0x90, 0x83),
        )

        /** 今のテーマの色。設定の「画面の色」が端末に合わせるなら、[context] の明暗に従う。 */
        fun current(context: Context): OverlayPalette {
            val dark = when (ThemePreferences.mode.value) {
                ThemeMode.SYSTEM -> context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            return if (dark) NIGHT else PAPER
        }
    }
}
