package app.plamotranslate.pipeline.conversation

import app.plamotranslate.llm.Language
import app.plamotranslate.llm.isChinese
import app.plamotranslate.llm.sameAs
import app.plamotranslate.pipeline.speech.SpeechLanguages
import app.plamotranslate.pipeline.speech.SpeechModel

/** 会話の画面に出す言語名。中国語は繁体字に広東語も含むことを示す。 */
val Language.conversationLabel: String
    get() = when (this) {
        Language.ZH_HANT -> "中国語（繁体字・広東語）"
        else -> label
    }

/**
 * 相手の言語の決め方。自動は、相手が話した言語を見つけて、自分の発話をその言語へ訳す。
 * [fixed] が null なら自動。
 */
data class PartnerMode(val fixed: Language?) {
    val auto: Boolean get() = fixed == null

    fun label(): String = fixed?.conversationLabel ?: "自動（相手が話した言語）"

    fun short(): String = when (fixed) {
        null -> "自動"
        Language.ZH -> "中国語・簡"
        Language.ZH_HANT -> "中国語・繁"
        else -> fixed.label
    }

    companion object {
        val AUTO = PartnerMode(null)
        val KOREAN = PartnerMode(Language.KO)
        val ENGLISH = PartnerMode(Language.EN)
        val CHINESE = PartnerMode(Language.ZH)
        val CHINESE_TRADITIONAL = PartnerMode(Language.ZH_HANT)

        /**
         * 自分が [self] のときに選べる相手。[languages] は会話で使える言語（選んだ音声認識モデルの言語、[SpeechModel.languages]）。
         * 自分と同じ言語は除く。広東語はPLaMoの言語に無いので、繁体字の中国語として訳す。
         */
        fun choices(self: Language, languages: List<Language>): List<PartnerMode> =
            listOf(AUTO) + languages.filterNot { it.sameAs(self) }.map(::PartnerMode)
    }
}

/** 1つの発話を、どの言語からどの言語へ訳すか。[mine] は自分の発話。[target] が null なら、相手の言語がまだ分からない（訳さない）。 */
data class Direction(val source: Language, val target: Language?, val mine: Boolean = source == Language.JA)

/**
 * 言語の状態と、発話ごとの訳す方向（docs/設計/会話翻訳.md「言語の判定と訳す方向」）。
 * 会話の話し手は自分と相手の2人なので、状態は2段だけにする。
 * - 相手不明（自動の始まり）：音声認識は、自分の言語か、聞けるほかの言語（[heard]）のどれかを決める。
 * - 相手確定：相手を固定したときは最初から。自動では、相手の発話が1回確定したら、その言語を相手にする。音声認識は自分と相手の2択。
 * 相手確定の後は、利用者が相手の言語を選び直すときだけ変わる（誤って決まったら選び直す。2026-11-15、作者の判断）。
 * 自分の言語の発話は相手の言語へ、それ以外は自分の言語へ訳す。相手不明の間の自分の発話は訳さない（相手が決まったら、会話の側がさかのぼって訳す）。
 *
 * 自動の相手を探す候補から、自分が日本語なら中国語を、自分が中国語なら日本語を除く（漢字の多い日本語を中国語と聞き違えて、
 * 自分の発話を相手と決めてしまうため）。その組み合わせの相手は、相手の言語を選んで話す。
 */
class LanguageRouter(
    mode: PartnerMode = PartnerMode.AUTO,
    self: Language = Language.JA,
    /** 音声認識が聞ける言語（[SpeechModel.languages]）。自動の相手を探す候補。 */
    private val heard: List<Language> = SpeechModel.DEFAULT.languages,
) {
    /** 自分の言語。変えたとき、相手が同じ言語なら自動に戻す。 */
    var self: Language = self
        set(value) {
            field = value
            if (mode.fixed?.sameAs(value) == true) mode = PartnerMode.AUTO
            if (found?.sameAs(value) == true) found = null
        }

    /** 相手の言語の決め方。選び直したら見つけた相手の言語を忘れる（自動を選び直すと、相手不明から始める）。 */
    var mode: PartnerMode = mode
        set(value) {
            field = value
            found = null
        }

    /** 自動のときに見つけた相手の言語。 */
    private var found: Language? = null

    /** 相手の言語。自動でまだ見つけていなければ null（相手不明）。 */
    val partner: Language? get() = mode.fixed ?: found

    /** 相手の言語を自動で見つけたか（画面に「自動で判定」と出す）。 */
    val partnerFound: Boolean get() = mode.auto && found != null

    /**
     * 中国語を日本語の聞き違いとみなさず、中国語として扱うか。自分か相手が中国語のときと、相手不明で中国語を探しているとき。
     * 音声認識が話し手の交代で区切るときも、このときだけ中国語を日本語と別に扱う。
     */
    val chinese: Boolean
        get() = self.isChinese || partner?.isChinese == true || (partner == null && candidates.any { it.isChinese })

    /** 相手不明の間に、相手として探す言語。 */
    private val candidates: List<Language>
        get() = heard.filterNot { it.sameAs(self) || confusable(it) }

    private fun confusable(language: Language): Boolean =
        (self == Language.JA && language.isChinese) || (self.isChinese && language == Language.JA)

    /** 会話の言語（音声認識が話し手を決める範囲）。相手不明なら自分と探す候補、相手確定なら自分と相手。 */
    val speechLanguages: SpeechLanguages
        get() {
            val partners = partner?.let { listOf(it) } ?: candidates
            return SpeechLanguages((partners + self).mapTo(HashSet()) { speechGroup(it) }, separateChinese = chinese, primary = speechGroup(self))
        }

    /** 確定した発話の方向を決める。自動で相手不明なら、相手の発話の言語を相手にする。 */
    fun route(text: String, langCode: String?): Direction = preview(text, langCode).also {
        if (mode.auto && found == null && !it.mine) found = it.source
    }

    /** 話している最中の途中結果の方向。相手の言語は決めない（判定が揺れやすいため）。 */
    fun preview(text: String, langCode: String?): Direction {
        val spoken = spokenLanguage(text, langCode, chinese)
        // 中国語なら、簡体字か繁体字（広東語）かは聞き取った言語に従う
        if (spoken.sameAs(self)) return Direction(if (self.isChinese) spoken else self, partner, mine = true)
        val fixed = mode.fixed
        val source = if (fixed == null || (fixed.isChinese && spoken.isChinese)) spoken else fixed
        return Direction(source, self, mine = false)
    }
    companion object {
        /** 音声認識の言語の組（`LanguageEvidence.group`）での言語。中国語は字体によらず1つの組。 */
        private fun speechGroup(language: Language): String = if (language.isChinese) "zh" else language.code

        /**
         * 認識器の言語判定を、会話で扱う言語に絞る。
         * 中国語・広東語の判定は、自分か相手が中国語のとき（[chinese]）だけ中国語とみなす。仮名を含めば日本語の聞き違いとする。
         * そうでなければ、漢字の多い日本語の聞き違いとして、判定なしと同じく認識した文字の種類で決める（漢字だけなら日本語）。
         */
        internal fun spokenLanguage(text: String, langCode: String?, chinese: Boolean = false): Language = when (langCode) {
            "ja" -> Language.JA
            "ko" -> Language.KO
            "en" -> Language.EN
            "zh" -> if (chinese && !hasKana(text)) Language.ZH else byScript(text)
            "yue" -> if (chinese && !hasKana(text)) Language.ZH_HANT else byScript(text)
            // 日・韓・英・中の外の言語は、言語の識別（Whisper）か文字の種類で決めた言語をそのまま使う
            else -> langCode?.let(Language::ofCode) ?: byScript(text)
        }

        private fun hasKana(text: String) = text.any { it in '぀'..'ヿ' }

        private fun byScript(text: String): Language {
            var hangul = 0
            var latin = 0
            var cjk = 0
            for (c in text) {
                when {
                    c in '가'..'힣' || c in '㄰'..'㆏' -> hangul++
                    c in 'A'..'Z' || c in 'a'..'z' -> latin++
                    c in '぀'..'ヿ' || c in '一'..'鿿' -> cjk++
                }
            }
            return when {
                hangul > 0 && hangul >= cjk -> Language.KO
                latin > cjk -> Language.EN
                else -> Language.JA
            }
        }
    }
}
