package app.plamotranslate.pipeline.conversation

import app.plamotranslate.pipeline.speech.SpeechModel
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LanguageRouterTest {
    @Test
    fun 自動では相手が話した言語へ自分の発話を訳し_相手が分かる前の自分の発話は訳さない() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.JA, null, mine = true), router.route("こんにちは", "ja"))
        assertEquals(Direction(Language.EN, Language.JA, mine = false), router.route("Hello", "en"))
        assertEquals(Direction(Language.JA, Language.EN, mine = true), router.route("元気です", "ja"))
        assertTrue(router.partnerFound)
    }

    @Test
    fun 途中結果では相手の言語を覚えない() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.EN, Language.JA, mine = false), router.preview("Hello", "en"))
        assertEquals(null, router.partner)
    }

    @Test
    fun 手動では判定が違っても固定した言語として扱う() {
        val router = LanguageRouter(PartnerMode.KOREAN)
        assertEquals(Direction(Language.KO, Language.JA, mine = false), router.route("Hello", "en"))
        assertEquals(Direction(Language.JA, Language.KO, mine = true), router.route("はい", "ja"))
    }

    @Test
    fun 中国語や判定なしは文字の種類で決める() {
        assertEquals(Language.JA, LanguageRouter.spokenLanguage("今日は晴れ", "zh"))
        assertEquals(Language.JA, LanguageRouter.spokenLanguage("天気", "yue"))
        assertEquals(Language.KO, LanguageRouter.spokenLanguage("안녕하세요", null))
        assertEquals(Language.EN, LanguageRouter.spokenLanguage("Thank you", null))
    }

    @Test
    fun 自動では中国語の判定を相手の言語にしない() {
        val router = LanguageRouter()
        assertEquals(Direction(Language.JA, null, mine = true), router.route("天気", "zh"))
        assertEquals(null, router.partner)
    }

    @Test
    fun 中国語の相手では中国語と広東語の判定を相手の発話とする() {
        val router = LanguageRouter(PartnerMode.CHINESE)
        assertEquals(Direction(Language.ZH, Language.JA, mine = false), router.route("你好", "zh"))
        assertEquals(Direction(Language.ZH_HANT, Language.JA, mine = false), router.route("你好嗎", "yue"))
        assertEquals(Direction(Language.JA, Language.ZH, mine = true), router.route("こんにちは", "ja"))
        // 仮名を含めば、中国語と判定されても日本語の聞き違いとみなす
        assertEquals(Direction(Language.JA, Language.ZH, mine = true), router.route("今日は晴れ", "zh"))
    }

    @Test
    fun 繁体字の相手には自分の発話を繁体字へ訳す() {
        val router = LanguageRouter(PartnerMode.CHINESE_TRADITIONAL)
        assertEquals(Direction(Language.JA, Language.ZH_HANT, mine = true), router.route("ありがとう", "ja"))
        assertEquals(Direction(Language.ZH_HANT, Language.JA, mine = false), router.route("多謝", "yue"))
    }

    @Test
    fun 自分が韓国語なら日本語の相手へ訳す() {
        val router = LanguageRouter(PartnerMode(Language.JA), self = Language.KO)
        assertEquals(Direction(Language.KO, Language.JA, mine = true), router.route("안녕하세요", "ko"))
        assertEquals(Direction(Language.JA, Language.KO, mine = false), router.route("こんにちは", "ja"))
    }

    @Test
    fun 自分と同じ言語は相手に選べず固定していたら自動に戻る() {
        assertEquals(false, PartnerMode.choices(Language.EN, SpeechModel.SENSE_VOICE.languages).contains(PartnerMode.ENGLISH))
        assertEquals(false, PartnerMode.choices(Language.ZH, SpeechModel.SENSE_VOICE.languages).any { it.fixed?.isChinese == true })
        val router = LanguageRouter(PartnerMode.ENGLISH)
        router.self = Language.EN
        assertEquals(PartnerMode.AUTO, router.mode)
        assertEquals("自動（相手が話した言語）", router.mode.label())
    }

    @Test
    fun 自分が中国語なら中国語の判定を自分の発話とする() {
        val router = LanguageRouter(PartnerMode.KOREAN, self = Language.ZH)
        assertEquals(true, router.chinese)
        assertEquals(Direction(Language.ZH, Language.KO, mine = true), router.route("你好", "zh"))
        assertEquals(Direction(Language.ZH_HANT, Language.KO, mine = true), router.route("多謝", "yue"))
        assertEquals(Direction(Language.KO, Language.ZH, mine = false), router.route("안녕", "ko"))
    }

    @Test
    fun 音声認識に渡す会話の言語は相手不明なら聞ける言語_相手確定なら自分と相手() {
        // 相手不明：聞ける言語のすべて。自分が日本語なら中国語は除く（漢字の多い日本語の聞き違い）
        assertEquals(setOf("ja", "ko", "en"), LanguageRouter().speechLanguages.groups)
        val qwen = LanguageRouter(heard = SpeechModel.QWEN3_ASR.languages).speechLanguages.groups!!
        assertTrue("ru" in qwen && "zh" !in qwen)
        assertEquals("ja", LanguageRouter().speechLanguages.primary)
        val fixed = LanguageRouter(PartnerMode.KOREAN)
        assertEquals(setOf("ja", "ko"), fixed.speechLanguages.groups)
        val chinese = LanguageRouter(PartnerMode.CHINESE_TRADITIONAL)
        assertEquals(setOf("ja", "zh"), chinese.speechLanguages.groups)
        assertTrue(chinese.speechLanguages.separateChinese)
    }

    @Test
    fun 自動は相手の発話が1回確定したら自分と相手の2言語にし_選び直すと相手不明に戻る() {
        val router = LanguageRouter(heard = SpeechModel.QWEN3_ASR.languages)
        router.route("Привет", "ru")
        assertEquals(setOf("ja", "ru"), router.speechLanguages.groups)
        assertEquals(Language.RU, router.partner)
        // 相手確定の後は、別の言語の判定が来ても相手を変えない（自分か相手の2択）
        router.route("Hello there", "en")
        assertEquals(Language.RU, router.partner)
        router.mode = PartnerMode.AUTO
        assertEquals(null, router.partner)
        assertTrue("ko" in router.speechLanguages.groups!!)
    }

    @Test
    fun 自分が日本語でも中国語でもなければ相手不明の間は中国語を中国語として聞く() {
        val r = LanguageRouter(PartnerMode.AUTO, Language.EN)
        assertEquals(true, r.chinese)
        assertEquals(Direction(Language.ZH, Language.EN, mine = false), r.route("我们说话", "zh"))
        assertEquals(Language.ZH, r.partner)
        // 自分が日本語なら中国語を探さず、漢字の多い日本語の聞き違いとして扱う
        assertEquals(false, LanguageRouter(PartnerMode.AUTO, Language.JA).chinese)
    }
}
