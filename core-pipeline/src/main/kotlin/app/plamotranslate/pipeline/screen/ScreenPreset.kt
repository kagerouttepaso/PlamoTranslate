package app.plamotranslate.pipeline.screen

import app.plamotranslate.pipeline.layout.Grouping

/**
 * 画面翻訳の工程の中身の組（docs/設計/画面翻訳.md「場面」）。メニューの行ごとの設定をまとめたもの。
 * [motion] は認識モードが動く画面か、[fit] は訳の枠を広げるか、[untilTouched] は訳の消え方が触れるまで残すか。
 */
data class ScreenSettings(
    val motion: Boolean,
    val grouping: Grouping,
    val fit: Boolean,
    val untilTouched: Boolean,
) {
    /** 撮り方・読み方・判定器の組（[SceneProfile]）。 */
    val scene: SceneProfile get() = SceneProfile.of(motion, untilTouched)
}

/**
 * 場面のプリセット：工程の中身の組に名前を付けたデータ。処理の分岐は書かない。
 * メニューで選ぶと組がまとめて決まり、行ごとに変えて組と合わなくなったら、どのプリセットでもない（カスタム）。
 * アプリごとに場面を自動で切り替えるときも、パッケージ名からここのプリセットを選んで同じ組を当てる。
 */
enum class ScreenPreset(val settings: ScreenSettings) {
    /** 漫画：吹き出しにまとめ、吹き出しいっぱいに広げる。 */
    MANGA(ScreenSettings(motion = false, grouping = Grouping.BUBBLE, fit = true, untilTouched = false)),

    /** Discord・ウェブ：文章の段落にまとめ、元の文字に合わせる。スクロールしたら消す。 */
    CHAT(ScreenSettings(motion = false, grouping = Grouping.TEXT, fit = false, untilTouched = false)),

    /** ゲーム：動く背景を消して読み、メニューの項目にまとめて項目いっぱいに広げ、触れるまで残す。 */
    GAME(ScreenSettings(motion = true, grouping = Grouping.MENU, fit = true, untilTouched = true)),
    ;

    companion object {
        /** [settings] と同じ組のプリセット。どれとも合わなければ null（カスタム）。 */
        fun matching(settings: ScreenSettings): ScreenPreset? = entries.firstOrNull { it.settings == settings }
    }
}
