package app.plamotranslate.screen

import android.content.Context
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.screen.ScreenPreset
import app.plamotranslate.pipeline.screen.ScreenSettings

/**
 * 画面翻訳の認識モード（2026-11-12、作者の要望。ゲームの画面で、文字の奥のキャラクターが動いて読み取りが崩れる）。
 * [MOTION] は数枚を続けて撮り、画素ごとの中央値で動く背景を消してから読む（[FrameStack]、docs/設計/画面翻訳.md「場面の設定」）。
 */
internal enum class ScreenRecognition(val menuName: String) {
    NORMAL("通常"),
    MOTION("動く画面"),
    ;

    companion object {
        fun parse(name: String?): ScreenRecognition = entries.firstOrNull { it.name == name } ?: NORMAL
    }
}

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

/**
 * 訳の消え方（2026-11-13、作者の要望「背景のキャラが元気よく走り回ると、画面が変わったとみなされて訳が消える」）。
 * [ON_TOUCH] は画面の動きでは消さず、触れたとき（のぞいて枠の下が変わっていれば）と画面の丸ごとの切り替わりで消す
 * （`SceneProfile.untilTouched`、docs/設計/画面翻訳.md「場面の設定」）。
 */
internal enum class ScreenDismissal(val menuName: String) {
    ON_CHANGE("画面が変わったら"),
    ON_TOUCH("触れるまで"),
    ;

    companion object {
        fun parse(name: String?): ScreenDismissal = entries.firstOrNull { it.name == name } ?: ON_CHANGE
    }
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
 * 画面翻訳の設定（原文の言語、認識モード、始め方、訳の消え方）。端末に残し、次に始めたときも引き継ぐ。
 * カメラ翻訳は言語そのものを選ぶので別に持つ（camera/CameraPreferences.kt）。
 * 翻訳モデルはアプリ全体で1つなので、ここではなく TranslationPreferences が持つ。
 */
internal class ScreenPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("screen_translate", Context.MODE_PRIVATE)

    /** 浮遊ボタンの長押しで選んだ原文の言語。読めなければ自動。 */
    var language: OcrLanguage
        get() = OcrLanguage.parse(prefs.getString(KEY_LANGUAGE, null))
        set(language) = prefs.edit().putString(KEY_LANGUAGE, language.name).apply()

    /** 浮遊ボタンの長押しで選んだ認識モード。読めなければ通常。 */
    var recognition: ScreenRecognition
        get() = ScreenRecognition.parse(prefs.getString(KEY_RECOGNITION, null))
        set(value) = prefs.edit().putString(KEY_RECOGNITION, value.name).apply()

    /** 浮遊ボタンの長押しで選んだ始め方。読めなければタップで訳す。 */
    var trigger: ScreenTrigger
        get() = ScreenTrigger.parse(prefs.getString(KEY_TRIGGER, null))
        set(value) = prefs.edit().putString(KEY_TRIGGER, value.name).apply()

    /** 浮遊ボタンの長押しで選んだ訳の消え方。読めなければ画面が変わったら消す。 */
    var dismissal: ScreenDismissal
        get() = ScreenDismissal.parse(prefs.getString(KEY_DISMISSAL, null))
        set(value) = prefs.edit().putString(KEY_DISMISSAL, value.name).apply()

    /** 訳の枠を広げるか（浮遊ボタンの長押しで切り替える。広げ方は単位の種類で決まる。overlay/SlotLayout.kt）。 */
    var fitBubbles: Boolean
        get() = prefs.getBoolean(KEY_FIT_BUBBLES, false)
        set(value) = prefs.edit().putBoolean(KEY_FIT_BUBBLES, value).apply()

    /** 浮遊ボタンの長押しで選んだ行のまとめ方。読めなければ吹き出し（0.7.0までの唯一のまとめ方）。 */
    var grouping: Grouping
        get() = Grouping.entries.firstOrNull { it.name == prefs.getString(KEY_GROUPING, null) } ?: Grouping.BUBBLE
        set(value) = prefs.edit().putString(KEY_GROUPING, value.name).apply()

    /**
     * 工程の中身の組（認識モード・まとめ方・訳の枠・訳の消え方）。場面のプリセットを選ぶと、まとめて書く。
     * 保存は行ごとの値のままなので、前の版の設定もそのまま読める。
     */
    var settings: ScreenSettings
        get() = ScreenSettings(
            motion = recognition == ScreenRecognition.MOTION,
            grouping = grouping,
            fit = fitBubbles,
            untilTouched = dismissal == ScreenDismissal.ON_TOUCH,
        )
        set(value) {
            recognition = if (value.motion) ScreenRecognition.MOTION else ScreenRecognition.NORMAL
            grouping = value.grouping
            fitBubbles = value.fit
            dismissal = if (value.untilTouched) ScreenDismissal.ON_TOUCH else ScreenDismissal.ON_CHANGE
        }

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
