package app.plamotranslate.pipeline.camera

import app.plamotranslate.llm.Language
import app.plamotranslate.pipeline.camera.TextSimilarity.key
import app.plamotranslate.pipeline.camera.TextSimilarity.similar
import app.plamotranslate.pipeline.layout.Grouping
import app.plamotranslate.pipeline.layout.TextLayout
import app.plamotranslate.pipeline.layout.TextLine
import app.plamotranslate.pipeline.layout.intersects
import app.plamotranslate.pipeline.layout.union
import app.plamotranslate.pipeline.screen.Box
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.TranslationStream

/**
 * 表示と翻訳の単位。追っている行（[LineTracker.Track]）を1つ以上まとめたもの。
 * [objectId] は「もの」の番号で、行が加わったり欠けたりしても、行の番号が重なる前の段落の番号を受け継ぐ。訳はこの番号に結び付ける。
 */
class CameraParagraph<L>(val objectId: Long, val lines: List<LineTracker.Track<L>>, join: (List<String>) -> String) {
    val trackIds: Set<Long> = lines.mapTo(HashSet()) { it.id }
    val box: Box = lines.drop(1).fold(lines.first().box) { acc, t -> acc.union(t.box) }

    /** 行をつないだ原文（今いちばん確かな読み）。 */
    val text: String = join(lines.map { it.consensus() })
    val look: L? get() = lines.first().look

    /** すべての行の読み取りがそろったか（[LineTracker.Track.settled]）。 */
    val settled: Boolean = lines.all { it.settled() }
}

/** 描く1つの段落。[translation] が null ならまだ訳が無い。 */
data class CameraBox<L>(val box: Box, val source: String, val look: L?, val translation: String?, val onScreen: Boolean)

/**
 * カメラ翻訳の制御（docs/設計/カメラ翻訳.md「制御」）。出来事（[Event]）を受け取り、状態を進めて、外でする作業（[Effect]）を返す
 * [handle] の1か所で、いつ何を訳すか・何を捨てるか・流れを開き直すかを決める。描く中身は [boxes] で読む。
 * 追った行の読み取りの履歴を持つので、状態はこの中に持つ（画面翻訳の `ScreenSession.reduce` と同じ考え方で、形だけ違う）。
 *
 * 訳す計画は「空いた分だけ、その時点で選んで渡す」（[dispatch]）。翻訳の流れに渡しておく文を、流れが無駄なく訳せる数
 * （[Event.EngineChanged] の intake。`intakeFor`）までにとどめ、訳が1件確定するたびに、その時点で見えている段落から
 * いちばん先に訳すべきものを選んで渡す。深い待ち行列を作らないので、カメラを動かしても一時停止しても、次に渡す文が変わるだけで、
 * 並べ替えも取り消しも要らない（docs/判断/2026-11-13_カメラ翻訳は空いた分だけその時点で選んで訳す.md）。
 *
 * [lineOf] は行を中核のまとめ方の入力にする（文字種の判定は文字認識の側にあるので、外から渡す）。
 */
class CameraSession<L>(
    private val lineOf: (String, Box) -> TextLine,
    private val join: (List<String>) -> String = CameraText::join,
) {
    /** 訳し方を変える設定。どれかが変わると前の訳は使わない。 */
    data class Settings(val source: Language?, val target: Language, val table: Boolean) {
        /** 翻訳の流れの設定。表・メニューのモードでは、短い語の意味を決める手がかりに背景を渡す。 */
        val options: TranslationStream.Options
            get() = TranslationStream.Options(source, target, context = if (table) TABLE_CONTEXT else null)
    }

    sealed interface Event<out L> {
        /** 1コマの読み取りと、画面に見えている範囲（正立した画像の座標。分からなければ null）。[still] は止めた画像の読み直し。 */
        data class Read<L>(val readings: List<Reading<L>>, val screen: Box?, val still: Boolean = false) : Event<L>
        data object Paused : Event<Nothing>
        data object Resumed : Event<Nothing>

        /** 端末の向きが変わった。座標の向きも変わるので、追っていた行を捨てる。 */
        data object Rotated : Event<Nothing>

        /** 訳し方の設定（原文の言語・訳す先・モード）。最初の1回も送る。 */
        data class SettingsChanged(val settings: Settings) : Event<Nothing>

        /** 翻訳モデルのエンジンが使えるようになった・切り替わった。[intake] は流れに一度に渡しておく数（`intakeFor`）。 */
        data class EngineChanged(val intake: Int) : Event<Nothing>

        /** 同じエンジンのまま、流れに一度に渡しておく数が変わった（同時に訳す数の設定）。 */
        data class IntakeChanged(val intake: Int) : Event<Nothing>

        /** [Effect.OpenStream] で頼んだ流れを開いた。 */
        data object StreamOpened : Event<Nothing>
        data class Partial(val id: Long, val text: String) : Event<Nothing>
        data class Final(val id: Long, val text: String) : Event<Nothing>
        data class Failed(val id: Long) : Event<Nothing>
    }

    sealed interface Effect {
        /** 前の流れを閉じ、[options] で翻訳の流れを開き直す。開いたら [Event.StreamOpened] を返す。 */
        data class OpenStream(val options: TranslationStream.Options) : Effect

        /** 翻訳の流れに文を渡す。 */
        data class Submit(val segments: List<Segment>) : Effect

        /** 止めた画像を1回読み直す（一時停止中に原文の言語を変えたとき）。読んだら [Event.Read] の still で返す。 */
        data object ReadStill : Effect

        /** 一時停止の後、見えている段落の訳がすべてそろった（一時停止のたびに1回）。 */
        data object VisibleComplete : Effect
    }

    private val tracker = LineTracker<L>()
    private val grouping = StableGrouping()
    private var settings: Settings? = null
    private var intake = 0
    private var streamReady = false
    private var screen: Box? = null
    private var paragraphs: List<CameraParagraph<L>> = emptyList()
    private var nextObjectId = 1L
    private var awaitingComplete = false

    /** 一時停止中か。読み取りを止め、見えている段落を固定する。訳し方は変えない。 */
    var paused = false
        private set

    /**
     * 確定した訳（原文のキー → 訳）。読み揺れはほぼ同じ文（[similar]）で吸収する。新しく使った順に [TRANSLATED_LIMIT] 件まで。
     * カメラを戻して、同じ文が別の段落として現れたときにも使う。
     */
    private val translated = object : LinkedHashMap<String, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > TRANSLATED_LIMIT
    }

    /** 段落の番号 → 最後に確定した訳。原文が変わって訳し直している間も、これを出し続ける。 */
    private val lastTranslation = HashMap<Long, String>()

    /** 流れに渡して、まだ確定していない文：文の番号 → 依頼。数は [intake] までにとどめる。 */
    private class Request(val objectId: Long, val key: String)
    private val inFlight = LinkedHashMap<Long, Request>()

    /** 訳している途中の訳（原文のキー → 途中の訳）。 */
    private val partial = HashMap<String, String>()

    /** 失敗した回数（原文のキー → 回数）。読み揺れした同じ文は同じ数に数える。 */
    private val failures = HashMap<String, Int>()
    private var nextSegmentId = 1L

    fun handle(event: Event<L>): List<Effect> {
        val effects = mutableListOf<Effect>()
        var skip: String? = null
        when (event) {
            is Event.Read -> {
                if (paused && !event.still) return emptyList()
                val updated = tracker.update(event.readings)
                if (!event.still) {
                    readCount += updated.size
                    blurredCount += updated.count { it.lastBlurred() }
                }
                event.screen?.let { screen = it }
                regroup(observe = true)
            }
            Event.Paused -> {
                paused = true
                awaitingComplete = true
                regroup()
            }
            Event.Resumed -> {
                paused = false
                awaitingComplete = false
                regroup()
            }
            Event.Rotated -> {
                tracker.clear()
                grouping.clear()
                regroup()
            }
            is Event.SettingsChanged -> {
                val before = settings
                settings = event.settings
                if (before != event.settings) {
                    // 原文の言語が変わると読み方（認識器）が変わるので、前の読み取りで多数決しない
                    val sourceChanged = before != null && before.source != event.settings.source
                    if (sourceChanged) {
                        tracker.clear()
                        grouping.clear()
                    }
                    // まとめ方（文章／表・メニュー）が変わったら、前のまとめ方の区切りを持ち越さない
                    if (before != null && before.table != event.settings.table) grouping.clear()
                    forgetTranslations()
                    regroup()
                    reopen(effects)
                    if (sourceChanged && paused) effects += Effect.ReadStill
                }
            }
            is Event.EngineChanged -> {
                // 翻訳モデルが変わったら、前のモデルの訳は使わない
                intake = event.intake
                forgetTranslations()
                reopen(effects)
            }
            is Event.IntakeChanged -> intake = event.intake
            Event.StreamOpened -> streamReady = true
            is Event.Partial -> {
                // 途中の訳では空きが変わらないので、渡し直さない
                inFlight[event.id]?.let { partial[it.key] = event.text }
                return emptyList()
            }
            is Event.Final -> inFlight.remove(event.id)?.let { r ->
                // 訳が空でも入れる（入れないと、枠が「訳待ち」のまま残り続ける）
                val text = event.text.trim().ifEmpty { UNTRANSLATABLE }
                translated[r.key] = text
                lastTranslation[r.objectId] = text
                partial.remove(r.key)
            }
            is Event.Failed -> inFlight.remove(event.id)?.let { r ->
                partial.remove(r.key)
                val k = failures.keys.firstOrNull { similar(it, r.key) } ?: r.key
                val count = (failures[k] ?: 0) + 1
                failures[k] = count
                if (count >= MAX_FAILURES) {
                    // 上限まで失敗したら、枠を「訳せませんでした」で終わらせる
                    translated[r.key] = UNTRANSLATABLE
                    lastTranslation[r.objectId] = UNTRANSLATABLE
                } else {
                    // 同じ文をすぐには頼み直さない（次の出来事でまた候補になる）
                    skip = r.key
                }
            }
        }
        effects += dispatch(skip)
        if (awaitingComplete && paused && streamReady && waitingOnScreen() == 0) {
            awaitingComplete = false
            effects += Effect.VisibleComplete
        }
        return effects
    }

    /** 描く段落。一時停止中は、読み取りが1回しかそろっていない行も出す。 */
    fun boxes(): List<CameraBox<L>> = paragraphs.map { p ->
        CameraBox(p.box, p.text, p.look, shownTranslation(p), onScreen(p.box))
    }

    /** 見えている段落のうち、まだ訳の無いものの数。 */
    fun waitingOnScreen(): Int = paragraphs.count { onScreen(it.box) && shownTranslation(it) == null }

    /** 流れに渡して、まだ確定していない文の数。 */
    val inFlightCount: Int get() = inFlight.size

    /** 渡した文の数と、そのうち訳のある段落の訳し直しの数（原文が変わったため）。区切りと原文の安定を実機で比べるため。 */
    var submittedCount = 0
        private set
    var resubmittedCount = 0
        private set

    /** 読めた行の数と、そのうちぶれたコマとして票を軽くした数（[LineTracker.Track.lastBlurred]）。鋭さの重みを実機で確かめるため。 */
    var readCount = 0
        private set
    var blurredCount = 0
        private set

    /**
     * 空いた分だけ、その時点でいちばん先に訳すべき段落を選んで渡す（[TranslationPlan.choose]）。
     * 見えている段落を、読み取りがそろったものを先に選び、空きが残れば、そろっていない段落も渡す。流れが空いているのに
     * 枠が埋まらないままにしないため（2026-11-13、作者の指摘「読み揺れが収まらない段落の訳が、いつまでも出ない」）。
     * 同じ段落は一度に1件しか渡さないので、読み揺れのたびに頼み直しはしない。見えていない段落は渡さない。
     */
    private fun dispatch(skip: String?): List<Effect> {
        if (!streamReady) return emptyList()
        val free = intake - inFlight.size
        if (free <= 0) return emptyList()
        val candidates = paragraphs.filter { onScreen(it.box) }
            .map { TranslationPlan.Candidate(it.objectId, it.text, it.box, it.settled) }
        val chosen = TranslationPlan.choose(candidates, screen, free) { c ->
            val k = key(c.text)
            k != skip && needsTranslation(c.id, k)
        }
        if (chosen.isEmpty()) return emptyList()
        val segments = chosen.map { c ->
            val id = nextSegmentId++
            submittedCount++
            if (c.id in lastTranslation) resubmittedCount++
            inFlight[id] = Request(c.id, key(c.text))
            Segment(id, c.text)
        }
        return listOf(Effect.Submit(segments))
    }

    /**
     * まだ訳を頼むべきか。同じ段落（番号）を渡していれば、原文が変わっていても待つ（読み揺れのたびに頼み直さない）。
     * 今の原文の訳が無く、ほぼ同じ文をほかの段落で渡してもおらず、失敗し続けてもいないときに頼む。
     */
    private fun needsTranslation(objectId: Long, k: String): Boolean =
        inFlight.values.none { it.objectId == objectId || similar(it.key, k) } &&
            translationOf(k) == null &&
            (failures.entries.firstOrNull { similar(it.key, k) }?.value ?: 0) < MAX_FAILURES

    /**
     * 段落に出す訳：今の原文の訳（途中を含む）、無ければ同じ段落の渡している依頼の途中の訳、無ければ同じ段落で最後に確定した訳。
     * 原文が読み揺れで変わっても、訳している途中の訳を消さない。
     */
    private fun shownTranslation(p: CameraParagraph<L>): String? = translationOf(key(p.text))
        ?: inFlight.values.firstOrNull { it.objectId == p.objectId }?.let { r -> partial[r.key]?.takeIf { it.isNotBlank() } }
        ?: lastTranslation[p.objectId]

    /** 訳を探す。読み取りはコマごとに1〜2文字揺れるので、ほぼ同じ文（[similar]）の訳も使う。途中の訳もあれば出す。 */
    private fun translationOf(k: String): String? {
        translated[k]?.let { return it }
        partial[k]?.takeIf { it.isNotBlank() }?.let { return it }
        return translated.entries.firstOrNull { similar(it.key, k) }?.value
            ?: partial.entries.firstOrNull { it.value.isNotBlank() && similar(it.key, k) }?.value
    }

    /**
     * 行を段落にまとめ直し、段落に番号を付ける。[observe] なら、このコマのまとめ方を区切りの多数決（[StableGrouping]）に足す
     * （見えている行は、読み取りがそろっていない行も数える）。段落は多数決で「つながっている」行の集まり。
     * 前の段落と行の番号が重なれば、いちばん多く重なる前の段落の番号を受け継ぐ。今の段落にも渡し中の文にも無い番号の訳は捨てる。
     */
    private fun regroup(observe: Boolean = false) {
        val rule = if (settings?.table == true) Grouping.MENU else Grouping.TEXT
        // 区切りは、見えている全部の行（読み取りがそろっていない行も）で決め、出す行には後で絞る
        val all = tracker.lines(includeUnsure = true)
        val textLines = all.map { lineOf(it.consensus(), it.box) }
        if (observe) {
            val units = TextLayout.units(textLines, rule)
            grouping.observe(units.map { u -> u.members.map { all[it].id } }, all.mapTo(HashSet()) { it.id }, tracker.aliveIds())
        }
        val shown = tracker.lines(includeUnsure = paused).mapTo(HashSet()) { it.id }
        val index = all.withIndex().associate { (i, t) -> t.id to i }
        val previous = paragraphs
        val claimed = HashSet<Long>()
        paragraphs = grouping.groups(all.map { it.id }).mapNotNull { group ->
            // 段落の中の行の並べ方は、中核のまとめ方と同じ規則（TextLayout.unitOf）にする
            val idx = group.filter { it in shown }.map { index.getValue(it) }
            if (idx.isEmpty()) return@mapNotNull null
            val ordered = TextLayout.unitOf(rule.kind, idx, textLines).members
            val members = ordered.map { all[it] }
            val ids = members.mapTo(HashSet()) { it.id }
            val inherited = previous.filter { it.objectId !in claimed }
                .maxByOrNull { p -> p.trackIds.count { it in ids } }
                ?.takeIf { p -> p.trackIds.any { it in ids } }?.objectId
            val id = inherited ?: nextObjectId++
            claimed += id
            CameraParagraph(id, members, join)
        }.sortedBy { it.box.top }
        val alive = paragraphs.mapTo(HashSet()) { it.objectId } + inFlight.values.map { it.objectId }
        lastTranslation.keys.retainAll(alive)
    }

    /** 訳し方が変わったので、前の訳と渡し中の文を捨てる。 */
    private fun forgetTranslations() {
        translated.clear()
        lastTranslation.clear()
        partial.clear()
        failures.clear()
        inFlight.clear()
    }

    /** 流れを開き直す（渡していた文は流れと一緒に消える）。開くまでは渡さない。 */
    private fun reopen(effects: MutableList<Effect>) {
        streamReady = false
        inFlight.clear()
        val s = settings ?: return
        if (intake > 0) effects += Effect.OpenStream(s.options)
    }

    private fun onScreen(b: Box): Boolean = screen?.intersects(b) ?: true

    companion object {
        /** 確定した訳を持つ数。 */
        const val TRANSLATED_LIMIT = 300

        /** 同じ文が何回失敗したら、「訳せませんでした」で終わらせるか。 */
        const val MAX_FAILURES = 2

        /** 訳が空・失敗し続けたときに枠に出す文。 */
        const val UNTRANSLATABLE = "（訳せませんでした）"

        /**
         * 表・メニューのモードで翻訳に渡す背景（PFML3の context）。短い語の意味を決める手がかり
         * （2026-11-06、エアコンのリモコンで「송풍」が宋風→送風になった。効果は限られる）。
         */
        const val TABLE_CONTEXT = "家電や機器のボタン・表示画面、メニュー、看板、表などに書かれた短い表記。"
    }
}
