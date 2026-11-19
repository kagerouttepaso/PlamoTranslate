package app.plamotranslate.overlay

import app.plamotranslate.llm.Language
import app.plamotranslate.ocr.OcrLanguage

/*
 * 文字認識で読める原文の言語と、言語ごとに動かす認識器の組（画面翻訳とカメラ翻訳で共通）。
 * 翻訳（PLaMo 3 Translate）は53言語を訳せるので、原文に選べる言語は文字認識が読める文字で決まる。
 * 同梱している認識器が読める文字は、ラテン文字・中国語・日本語・韓国語の4つ（docs/設計/カメラ翻訳.md「原文の言語」）。
 * アラビア文字・キリル文字・タイ文字・デーヴァナーガリーなどの言語は、読める認識器が無いので選べない。
 * 原文の言語は、機能の中では Language で持ち（自動は null）、文字認識に渡すときだけ認識器の組（OcrLanguage）にする。
 */

/** ラテン文字で書く言語。ML Kitの英字の認識器で読む。セルビア語は看板がキリル文字のことが多いので入れない。 */
private val LATIN_SCRIPT = setOf(
    Language.EN, Language.AZ, Language.IT, Language.ID, Language.UZ, Language.ET, Language.NL, Language.CA,
    Language.HR, Language.JV, Language.ES, Language.SK, Language.SL, Language.SW, Language.CEB, Language.TL,
    Language.DE, Language.HU, Language.EU, Language.FR, Language.VI, Language.PL, Language.MS, Language.LV,
    Language.LT,
)

/** その言語の原文を読むのに動かす認識器の組。読める認識器が無ければ null。 */
internal val Language.ocrLanguage: OcrLanguage?
    get() = when (this) {
        Language.JA -> OcrLanguage.JAPANESE
        Language.KO -> OcrLanguage.KOREAN
        Language.ZH, Language.ZH_HANT -> OcrLanguage.CHINESE
        in LATIN_SCRIPT -> OcrLanguage.LATIN
        else -> null
    }

/** 原文の言語（null は自動）で動かす認識器の組。自動ならすべての認識器を動かす。 */
internal val Language?.recognizers: OcrLanguage get() = this?.ocrLanguage ?: OcrLanguage.AUTO

/** 原文に選べる言語（自動を除く）。並びは [Language] の順（よく使う言語が先、残りは五十音順）。 */
internal val READABLE_SOURCES: List<Language> = Language.entries.filter { it.ocrLanguage != null }

/** 選択肢に出す短い名前。中国語（簡体字）は、繁体字と並べないところでは「中国語」と出す。 */
internal val Language.shortName: String
    get() = if (this == Language.ZH) "中国語" else label

/** 原文の言語の保存値。言語コードで、自動は "auto"。 */
internal val Language?.storedCode: String get() = this?.code ?: AUTO_CODE

/**
 * 保存値から原文の言語を戻す。null は自動。[stored]（言語コード）が無ければ、以前の版が認識器の組の名前で保存した
 * [legacy] を、同じ組で読む言語に読み替える（ラテン文字は英語）。読めない値と [allowed] に無い言語は自動にする。
 */
internal fun storedSource(stored: String?, legacy: String?, allowed: Collection<Language>): Language? {
    val language = if (stored == null) OcrLanguage.parse(legacy).legacySource else Language.ofCode(stored)
    return language?.takeIf { it in allowed }
}

private const val AUTO_CODE = "auto"

private val OcrLanguage.legacySource: Language?
    get() = when (this) {
        OcrLanguage.AUTO -> null
        OcrLanguage.CHINESE -> Language.ZH
        OcrLanguage.KOREAN -> Language.KO
        OcrLanguage.LATIN -> Language.EN
        OcrLanguage.JAPANESE -> Language.JA
    }
