package app.plamotranslate.screen

import android.content.Context
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.screen.Dismissal
import app.plamotranslate.pipeline.screen.Recognition
import app.plamotranslate.pipeline.screen.ScreenPreset
import app.plamotranslate.pipeline.screen.ScreenSettings

/**
 * 画面翻訳の始め方（2026-11-12、作者の要望「漫画のページ送りや、Discordでスクロールが止まったら、タップ不要で訳す」）。
 * [AUTO] は画面が変わったら訳を消し、画面が止まったら訳す（[ScreenChange]、docs/設計/画面翻訳.md「判定器」）。
 */
internal enum class ScreenTrigger(val menuName: String) {
    TAP("タップ"),
    AUTO("自動"),
    ;

    companion object {
        fun parse(name: String?): ScreenTrigger = entries.firstOrNull { it.name == name } ?: TAP
    }
}

/** 認識モードのメニュー名（2026-11-12、作者の要望。ゲームの画面で、文字の奥のキャラクターが動いて読み取りが崩れる）。 */
internal val Recognition.menuName: String
    get() = when (this) {
        Recognition.NORMAL -> "通常"
        Recognition.MOTION -> "動く画面"
    }

/** 訳の消え方のメニュー名（2026-11-13、作者の要望「背景のキャラが元気よく走り回ると、画面が変わったとみなされて訳が消える」）。 */
internal val Dismissal.menuName: String
    get() = when (this) {
        Dismissal.ON_CHANGE -> "画面が変わったら"
        Dismissal.ON_TOUCH -> "触れるまで"
    }

/** 場面のプリセットのメニュー名。 */
internal val ScreenPreset.menuName: String
    get() = when (this) {
        ScreenPreset.MANGA -> "漫画"
        ScreenPreset.CHAT -> "チャット"
        ScreenPreset.GAME -> "ゲーム"
    }

/** まとめ方のメニュー名。 */
internal val Grouping.menuName: String
    get() = when (this) {
        Grouping.BUBBLE -> "吹き出し"
        Grouping.TEXT -> "文章"
        Grouping.MENU -> "メニュー"
    }

/**
 * 画面翻訳の設定（原文の言語、始め方、工程の中身の組（認識モード・まとめ方・訳の枠・訳の消え方））。端末に残し、次に始めたときも引き継ぐ。
 * カメラ翻訳は言語そのものを選ぶので別に持つ（camera/CameraPreferences.kt）。
 * 翻訳モデルはアプリ全体で1つなので、ここではなく TranslationPreferences が持つ。
 */
internal class ScreenPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screen_translate", Context.MODE_PRIVATE)

    /** 浮遊ボタンの長押しで選んだ原文の言語。読めなければ自動。 */
    var language: OcrLanguage
        get() = OcrLanguage.parse(prefs.getString(KEY_LANGUAGE, null))
        set(language) = prefs.edit().putString(KEY_LANGUAGE, language.name).apply()

    /** 浮遊ボタンの長押しで選んだ始め方。読めなければタップで訳す。 */
    var trigger: ScreenTrigger
        get() = ScreenTrigger.parse(prefs.getString(KEY_TRIGGER, null))
        set(value) = prefs.edit().putString(KEY_TRIGGER, value.name).apply()

    /**
     * 工程の中身の組（認識モード・まとめ方・訳の枠・訳の消え方）。場面のプリセットを選ぶと、まとめて書く。
     * 保存は行ごとの値（列挙の名前）で、前の版の設定もそのまま読める。読めなければ0.7.0までの既定（通常・吹き出し・広げない・画面が変わったら）。
     */
    var settings: ScreenSettings
        get() = ScreenSettings(
            recognition = parse(KEY_RECOGNITION, Recognition.NORMAL),
            grouping = parse(KEY_GROUPING, Grouping.BUBBLE),
            fit = prefs.getBoolean(KEY_FIT_BUBBLES, false),
            dismissal = parse(KEY_DISMISSAL, Dismissal.ON_CHANGE),
        )
        set(value) = prefs.edit()
            .putString(KEY_RECOGNITION, value.recognition.name)
            .putString(KEY_GROUPING, value.grouping.name)
            .putBoolean(KEY_FIT_BUBBLES, value.fit)
            .putString(KEY_DISMISSAL, value.dismissal.name)
            .apply()

    private inline fun <reified T : Enum<T>> parse(key: String, default: T): T =
        enumValues<T>().firstOrNull { it.name == prefs.getString(key, null) } ?: default

    /**
     * 訳の枠を透かさないか（設定の画面で切り替える。ユーザー補助のサービス OverlayAccessibilityService を使う）。
     * オンでもユーザー補助が有効でなければ、今までどおり不透明度0.8で置く。
     */
    var opaqueOverlay: Boolean
        get() = prefs.getBoolean(KEY_OPAQUE_OVERLAY, false)
        set(value) = prefs.edit().putBoolean(KEY_OPAQUE_OVERLAY, value).apply()

    private companion object {
        const val KEY_LANGUAGE = "language"
        const val KEY_RECOGNITION = "recognition"
        const val KEY_TRIGGER = "trigger"
        const val KEY_DISMISSAL = "dismissal"
        const val KEY_FIT_BUBBLES = "fit_bubbles"
        const val KEY_GROUPING = "grouping"
        const val KEY_OPAQUE_OVERLAY = "opaque_overlay"
    }
}
