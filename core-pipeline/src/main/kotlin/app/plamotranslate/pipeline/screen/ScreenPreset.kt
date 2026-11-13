package app.plamotranslate.pipeline.screen

import app.plamotranslate.pipeline.layout.Grouping

/** 認識モード。[MOTION] は数枚を続けて撮り、画素ごとの中央値で動く背景を消してから読む（docs/設計/画面翻訳.md「場面の設定」）。 */
enum class Recognition { NORMAL, MOTION }

/** 訳の消え方。[ON_TOUCH] は画面の動きでは消さず、触れたときと画面の丸ごとの切り替わりで消す（[SceneProfile.untilTouched]）。 */
enum class Dismissal { ON_CHANGE, ON_TOUCH }

/**
 * 画面翻訳の工程の中身の組（docs/設計/画面翻訳.md「場面」）。メニューの行ごとの設定をまとめたもの。[fit] は訳の枠を広げるか。
 */
data class ScreenSettings(
    val recognition: Recognition,
    val grouping: Grouping,
    val fit: Boolean,
    val dismissal: Dismissal,
) {
    /** 撮り方・読み方・判定器の組（[SceneProfile]）。 */
    val scene: SceneProfile get() = SceneProfile.of(recognition == Recognition.MOTION, dismissal == Dismissal.ON_TOUCH)
}

/**
 * 場面のプリセット：工程の中身の組に名前を付けたデータ。処理の分岐は書かない。
 * メニューで選ぶと組がまとめて決まり、行ごとに変えて組と合わなくなったら、どのプリセットでもない（カスタム）。
 * アプリごとに場面を自動で切り替えるときも、パッケージ名からここのプリセットを選んで同じ組を当てる。
 */
enum class ScreenPreset(val settings: ScreenSettings) {
    /** 漫画：吹き出しにまとめ、吹き出しいっぱいに広げる。 */
    MANGA(ScreenSettings(Recognition.NORMAL, Grouping.BUBBLE, fit = true, Dismissal.ON_CHANGE)),

    /** チャット（Discord・ウェブ）：文章の段落にまとめ、元の文字に合わせる。スクロールしたら消す。 */
    CHAT(ScreenSettings(Recognition.NORMAL, Grouping.TEXT, fit = false, Dismissal.ON_CHANGE)),

    /** ゲーム：動く背景を消して読み、メニューの項目にまとめて項目いっぱいに広げ、触れるまで残す。 */
    GAME(ScreenSettings(Recognition.MOTION, Grouping.MENU, fit = true, Dismissal.ON_TOUCH)),
    ;

    companion object {
        /** [settings] と同じ組のプリセット。どれとも合わなければ null（カスタム）。 */
        fun matching(settings: ScreenSettings): ScreenPreset? = entries.firstOrNull { it.settings == settings }
    }
}
