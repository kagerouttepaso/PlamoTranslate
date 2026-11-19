package app.plamotranslate.screen

import android.content.Context
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.screen.Dismissal
import app.plamotranslate.pipeline.screen.Recognition
import app.plamotranslate.pipeline.screen.ScreenPreset
import app.plamotranslate.pipeline.screen.ScreenSettings

/**
 * フローティングボタンの長押しのメニューの行（docs/設計/画面翻訳.md「操作」）。今の設定を読んで行を作り、選ばれた設定を保存して [actions] へ知らせる。
 * 状態遷移・判定器・フローティングボタンには触れない（コールバックを受けたサービスが行う）。選択肢を押すたびに作り直す（[build]）。
 */
internal class ScreenMenuEntries(
    private val context: Context,
    private val preferences: ScreenPreferences,
    private val actions: Actions,
) {
    /** 設定を変えたときにサービスが行うこと。 */
    interface Actions {
        /** フローティングボタンの字が変わる設定（原文の言語・始め方）を変えた。 */
        fun labelChanged()
        fun triggerChanged(trigger: ScreenTrigger)

        /** 工程の中身の組（認識モード・まとめ方・訳の枠・訳の消え方）を変えた。 */
        fun settingsChanged()
        fun switchModel(model: TranslationModel)
        fun stop()
    }

    fun build(): List<MenuEntry> = buildList {
        val settings = preferences.settings
        val preset = ScreenPreset.matching(settings)
        // 場面を選ぶと、下の工程の中身の行がまとめて変わる。行を個別に変えて組と合わなくなったら「カスタム」
        add(MenuEntry.Choice("場面", ScreenPreset.entries.map { p ->
            Option(p.menuName, checked = p == preset) { change(p.settings) }
        } + listOfNotNull(
            if (preset == null) Option("カスタム", checked = true, enabled = false, disabledHint = "上の場面を選ぶと、下の行がまとめて変わります") {} else null,
        )))
        // 自動とよく使う言語を行に並べ、ほかの読める言語は「他の言語」のページに並べる（行に並べるとメニューが縦に長くなる）。
        // 画面翻訳は日本語へ訳すので、日本語は原文の候補に出さない
        val source = preferences.source
        fun sourceOption(lang: Language?) = Option(lang.screenMenuName, checked = lang == source) { chooseSource(lang) }
        val other = source?.takeIf { it in SCREEN_OTHER_SOURCES }
        add(MenuEntry.Choice(
            "原文の言語",
            (listOf<Language?>(null) + SCREEN_QUICK_SOURCES).map(::sourceOption),
            // サブメニューの言語を選んでいれば、その言語の名前で選択中の表示にする（行はすでに幅いっぱいで、
            // この選択肢はいつも2段目に1つだけ置かれるので、長い名前でも段の数は変わらない）
            more = OptionPage(other?.label ?: "他の言語", checked = other != null, "原文の言語（他の言語）") {
                SCREEN_OTHER_SOURCES.map(::sourceOption)
            },
        ))
        add(MenuEntry.Choice("始め方", ScreenTrigger.entries.map { t ->
            Option(t.menuName, checked = t == preferences.trigger) {
                preferences.trigger = t
                actions.labelChanged()
                actions.triggerChanged(t)
            }
        }))
        add(MenuEntry.Choice("認識モード", Recognition.entries.map { r ->
            Option(r.menuName, checked = settings.recognition == r) { change(settings.copy(recognition = r)) }
        }))
        add(MenuEntry.Choice("まとめ方", Grouping.entries.map { g ->
            Option(g.menuName, checked = settings.grouping == g) { change(settings.copy(grouping = g)) }
        }))
        add(MenuEntry.Choice("訳の枠", listOf(
            Option("文字に合わせる", checked = !settings.fit) { change(settings.copy(fit = false)) },
            Option("広げる", checked = settings.fit) { change(settings.copy(fit = true)) },
        )))
        add(MenuEntry.Choice("訳の消え方", Dismissal.entries.map { d ->
            Option(d.menuName, checked = settings.dismissal == d) { change(settings.copy(dismissal = d)) }
        }))
        // 翻訳モデルはアプリ全体で1つなので、ここで変えると会話・カメラ・テキストの画面のモデルも変わる
        val engineState = EngineHolder.state.value
        val current = (engineState as? ModelState.Ready)?.model ?: EngineHolder.selected(context)
        add(MenuEntry.Choice("翻訳モデル", TranslationModel.entries.map { m ->
            val present = m.isPresent(context)
            Option(
                m.label,
                checked = m == current,
                enabled = present && engineState !is ModelState.Loading,
                disabledHint = if (present) "翻訳モデルを読み込んでいます" else "${m.label}は設定の画面からダウンロードしてください",
            ) { if (m != current) actions.switchModel(m) }
        }))
        add(MenuEntry.Action("画面翻訳を終了") { actions.stop() })
    }

    private fun chooseSource(language: Language?) {
        preferences.source = language
        actions.labelChanged()
    }

    private fun change(settings: ScreenSettings) {
        preferences.settings = settings
        actions.settingsChanged()
    }
}
