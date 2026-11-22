package app.plamotranslate.desktop

import app.plamotranslate.desktop.pdf.PdfSpread
import app.plamotranslate.desktop.pdf.PdfZoom
import app.plamotranslate.llm.Language
import app.plamotranslate.log.Logs
import app.plamotranslate.pipeline.EventLoop
import app.plamotranslate.pipeline.pdf.PdfEffect
import app.plamotranslate.pipeline.pdf.PdfEvent
import app.plamotranslate.pipeline.pdf.PdfPhase
import app.plamotranslate.pipeline.pdf.PdfSession
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.Path

/** ビューアの見え方。並べ方・拡大率・見出しの欄は次に開いたときも同じにする。 */
internal data class PdfViewSettings(
    val zoom: PdfZoom = PdfZoom.FitWidth,
    val spread: PdfSpread = PdfSpread.SINGLE,
    val outline: Boolean = true,
)

/** 画面だけが持つ状態。[showTranslation] は訳を書き込んだページを訳文で見せるか（原文/訳文の切り替え）。 */
internal data class PdfViewState(
    val settings: PdfViewSettings = PdfViewSettings(),
    val showTranslation: Boolean = true,
    val saving: Boolean = false,
    val notice: String? = null,
    val noticeIsError: Boolean = false,
)

/**
 * PDF 翻訳の画面の発表役。訳す順番と書き込みの判断は中核の [PdfSession]、文書の読み書きと版は [PdfReader]、
 * ページの描画・拡大・リンク・選択・検索はビューア（PdfScreen）が受け持つ。ここはそれらをつなぎ、
 * 翻訳の依頼を [TranslationStream] へ流し、ビューアの見え方を保存する。
 */
internal class PdfPresenter(
    private val scope: CoroutineScope,
    private val reader: PdfReader,
    private val openTranslation: (CoroutineScope, TranslationStream.Options) -> TranslationStream,
    private val chooseFile: () -> Path?,
    private val chooseSave: (String) -> Path? = { null },
    private val preferences: PdfViewPreferences? = null,
) {
    private val session = PdfSession()
    private val mutableState = MutableStateFlow(session.state)
    val state = mutableState.asStateFlow()
    val documents = reader.documents
    private val mutableView = MutableStateFlow(PdfViewState(preferences?.load() ?: PdfViewSettings()))
    val view = mutableView.asStateFlow()
    private val events = EventLoop<PdfEvent, PdfEffect>({ event ->
        val before = session.state.phase
        session.reduce(event).also {
            mutableState.value = session.state
            val after = session.state.phase
            // 止めた・訳し終えたら、間隔を待たずに最新のページを画面と保存に出す。
            if (before == PdfPhase.Translating && after != PdfPhase.Translating) reader.flush()
        }
    }, ::perform)

    private var stream: TranslationStream? = null
    private var subscription: Job? = null
    private val batches = mutableMapOf<Long, Long>()

    init {
        scope.launch { reader.events.collect(events::dispatch) }
    }

    fun choose() { chooseFile()?.let(::open) }

    /** ビューアの中心にあるページ。訳す順番はここから近い順になる。 */
    fun page(index: Int) {
        if (index != session.state.page) events.dispatch(PdfEvent.Page(index))
    }

    fun selectTarget(target: Language) = events.dispatch(PdfEvent.Target(target))
    fun setParallel(slots: Int) = events.dispatch(PdfEvent.Parallel(slots))

    fun start() {
        mutableView.value = mutableView.value.copy(showTranslation = true, notice = null)
        events.dispatch(PdfEvent.Start)
    }

    fun stop() = events.dispatch(PdfEvent.Stop)
    fun retry() = events.dispatch(PdfEvent.Retry)

    fun showTranslation(show: Boolean) {
        mutableView.value = mutableView.value.copy(showTranslation = show)
    }

    fun setZoom(zoom: PdfZoom) = updateSettings { it.copy(zoom = zoom) }
    fun setSpread(spread: PdfSpread) = updateSettings { it.copy(spread = spread) }
    fun toggleOutline() = updateSettings { it.copy(outline = !it.outline) }
    fun dismissNotice() {
        mutableView.value = mutableView.value.copy(notice = null)
    }

    /** 今までに訳したページを入れた PDF を保存する。訳している途中でも、書き込み済みのページで保存できる。 */
    fun save() {
        val current = session.state
        val name = current.name ?: return
        if (mutableView.value.saving) return
        val path = chooseSave(savedName(name, current.target)) ?: return
        mutableView.value = mutableView.value.copy(saving = true, notice = null)
        scope.launch {
            val notice = try {
                val finished = reader.save(path, session.state.titleTranslations)
                val pages = session.state.writtenPages.size
                buildString {
                    append("${path.fileName} に保存しました（訳したページ $pages / ${session.state.pageCount}")
                    if (finished.translatedBookmarks > 0) append("・見出し ${finished.translatedBookmarks} 件")
                    append("）")
                } to false
            } catch (error: Exception) {
                Logs.w("PDF を保存できません", error)
                "保存できませんでした：${error.message ?: error.javaClass.simpleName}" to true
            }
            mutableView.value = mutableView.value.copy(saving = false, notice = notice.first, noticeIsError = notice.second)
        }
    }

    fun drop(files: List<String>) {
        files.firstNotNullOfOrNull { value ->
            try {
                val file = if (value.startsWith("file:", ignoreCase = true)) Path.of(java.net.URI.create(value)) else Path.of(value)
                file.takeIf { it.fileName?.toString()?.endsWith(".pdf", ignoreCase = true) == true }
            } catch (_: IllegalArgumentException) {
                null
            }
        }?.let(::open)
    }

    private fun open(path: Path) {
        val normalized = path.toAbsolutePath().normalize()
        mutableView.value = mutableView.value.copy(showTranslation = true, notice = null)
        events.dispatch(PdfEvent.Open(normalized.toString(), normalized.fileName.toString()))
    }

    private fun updateSettings(change: (PdfViewSettings) -> PdfViewSettings) {
        val settings = change(mutableView.value.settings)
        if (settings == mutableView.value.settings) return
        mutableView.value = mutableView.value.copy(settings = settings)
        preferences?.save(settings)
    }

    private fun perform(effect: PdfEffect) {
        when (effect) {
            is PdfEffect.Read -> reader.read(effect.request)
            is PdfEffect.WritePage -> reader.write(effect)
            PdfEffect.StopTranslation -> {
                batches.clear()
                subscription?.cancel()
                subscription = null
                stream?.close()
                stream = null
            }
            is PdfEffect.Translate -> {
                val opened = stream ?: openTranslation(scope, TranslationStream.Options(source = null, target = effect.target))
                    .also { active ->
                        stream = active
                        subscription = scope.launch {
                            active.updates.collect { update ->
                                val batch = if (update is SegmentUpdate.Partial) batches[update.id] else batches.remove(update.id)
                                if (batch != null) events.dispatch(PdfEvent.Translated(batch, update))
                            }
                        }
                    }
                effect.segments.forEach { batches[it.id] = effect.batch }
                opened.submitBatch(effect.segments)
            }
        }
    }

    private companion object {
        /** 「元の名前（日本語訳）.pdf」。元のファイルを上書きしない名前にする。 */
        fun savedName(name: String, target: Language): String =
            "${name.removeSuffix(".pdf").removeSuffix(".PDF")}（${target.label}訳）.pdf"
    }
}
