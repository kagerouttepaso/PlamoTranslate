package app.plamotranslate.screen

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.sameAs
import app.plamotranslate.overlay.READABLE_SOURCES
import app.plamotranslate.overlay.shortName

/*
 * 画面翻訳の原文の言語（フローティングボタンの長押しで選ぶ。2026-11-05、作者の要望）。null は自動。
 * 2026-11-19、作者の要望で、文字認識が読める言語をすべて「他の言語」から選べるようにした。
 * 選べる言語と言語ごとに動かす認識器は、カメラ翻訳と共通の表（overlay/SourceLanguages.kt）で決まる。
 */

/** 画面翻訳の訳す先。今は日本語に固定する。 */
internal val SCREEN_TARGET = Language.JA

/** 原文に選べる言語（自動を除く）。文字認識が読める言語のうち、訳す先と同じ言語を除く。 */
internal val SCREEN_SOURCES: List<Language> = READABLE_SOURCES.filterNot { it.sameAs(SCREEN_TARGET) }

/** メニューの行に直接並べる言語。 */
internal val SCREEN_QUICK_SOURCES: List<Language> = listOf(Language.ZH, Language.KO, Language.EN).filter { it in SCREEN_SOURCES }

/** 「他の言語」のページに並べる言語（行に並べた言語を除く）。 */
internal val SCREEN_OTHER_SOURCES: List<Language> = SCREEN_SOURCES - SCREEN_QUICK_SOURCES.toSet()

/** メニューに出す名前。 */
internal val Language?.screenMenuName: String get() = this?.shortName ?: "自動"

/**
 * フローティングボタンの右下のバッジ。原文の言語を決めたときだけ出す（自動なら null）。
 * 日・中・韓・英と繁体字は漢字1文字、ほかの言語は言語コードの先頭2文字（大文字）。
 */
internal fun Language?.buttonBadge(): String? = when (this) {
    null -> null
    Language.JA -> "日"
    Language.ZH -> "中"
    Language.ZH_HANT -> "繁"
    Language.KO -> "韓"
    Language.EN -> "英"
    else -> code.take(2).uppercase()
}
