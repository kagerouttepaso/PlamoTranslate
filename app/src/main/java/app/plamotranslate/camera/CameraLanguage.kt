package app.plamotranslate.camera

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.sameAs
import app.plamotranslate.overlay.READABLE_SOURCES

/*
 * カメラ翻訳の原文と訳す先の言語（2026-11-08、作者の要望「ライブラリとモデルで対応できる言語は全て選べるように」）。
 * 原文に選べる言語と、言語ごとに動かす認識器は、画面翻訳と共通の表（overlay/SourceLanguages.kt）で決まる。
 * 訳す先と同じ言語は原文に選ばない（日本語へ訳すときは日本語を、英語へ訳すときは英語を出さない）。
 */

/** 訳す先に選べる言語。PLaMoはどの言語へも訳せるので、全53言語。 */
internal val CAMERA_TARGETS: List<Language> = Language.entries

/** 読める原文の言語（自動を除く）。並びは [Language] の順（よく使う言語が先、残りは五十音順）。 */
internal val CAMERA_SOURCES: List<Language> = READABLE_SOURCES

/** 訳す先が [target] のときに選べる原文の言語。訳す先と同じ言語は除く。 */
internal fun cameraSources(target: Language): List<Language> = CAMERA_SOURCES.filterNot { it.sameAs(target) }

/** 下の欄にチップで並べる言語（訳す先と同じ言語を除いて3つ）。ほかの言語は「他の言語」のメニューから選ぶ。 */
internal fun cameraQuickSources(target: Language): List<Language> =
    listOf(Language.JA, Language.KO, Language.EN, Language.ZH).filterNot { it.sameAs(target) }.take(3)
