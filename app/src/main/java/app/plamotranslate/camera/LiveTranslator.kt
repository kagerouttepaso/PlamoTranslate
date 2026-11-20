package app.plamotranslate.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.llm.Language
import app.plamotranslate.llm.TranslationEngine
import app.plamotranslate.log.Logs
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.overlay.BoxColors
import app.plamotranslate.overlay.TextBlock
import app.plamotranslate.overlay.recognizers
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.overlay.textLine
import app.plamotranslate.overlay.toBox
import app.plamotranslate.overlay.toRect
import app.plamotranslate.pipeline.EventLoop
import app.plamotranslate.pipeline.camera.CameraSession
import app.plamotranslate.pipeline.camera.CameraSession.Effect
import app.plamotranslate.pipeline.camera.CameraSession.Event
import app.plamotranslate.pipeline.camera.CameraSession.Settings
import app.plamotranslate.pipeline.camera.Reading
import app.plamotranslate.pipeline.camera.Sharpness
import app.plamotranslate.pipeline.screen.Box
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.pipeline.translation.intakeFor
import app.plamotranslate.translation.Translations
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * カメラの1コマの訳の枠。[rect] は正立した画像（[LiveFrame.rotation]、[CameraGeometry]）の座標。
 * [translation] が null ならまだ訳していない。
 */
data class LiveBox(
    val rect: Rect,
    val source: String,
    val colors: BoxColors,
    val translation: String?,
    /** 画面に見えている範囲に入っているか（文字認識の画像は4:3で、縦長の画面からは左右がはみ出す）。 */
    val onScreen: Boolean = true,
)

/**
 * 表示する1コマ：縦持ちの向きの画像（一時停止のときにそのまま出す）と、その上の訳の枠。
 * [rotation] は端末の傾き（時計回り、0・90・180・270）。文字認識と枠の座標は、[bitmap] をこの角度だけ回した正立した画像のもの
 * （2026-11-06、作者の要望「横向きでも読みたい」）。
 */
class LiveFrame(
    val bitmap: Bitmap,
    val boxes: List<LiveBox>,
    val ocrMillis: Long,
    val rotation: Int = 0,
    /** 表・メニューのモードか（訳の枠を広げずに描く）。 */
    val table: Boolean = false,
) {
    fun with(boxes: List<LiveBox>, table: Boolean = this.table) = LiveFrame(bitmap, boxes, ocrMillis, rotation, table)

    /** 正立した画像の幅と高さ。 */
    val uprightWidth: Int get() = if (rotation % 180 == 0) bitmap.width else bitmap.height
    val uprightHeight: Int get() = if (rotation % 180 == 0) bitmap.height else bitmap.width
}

/**
 * カメラ翻訳のアダプター（docs/設計/カメラ翻訳.md）。コマを読み、端末の出来事を中核の制御（[CameraSession]）へ渡し、
 * 返ってきた作業（翻訳のストリームを開く・文を渡す・止めた画像を読み直す）を行い、描く中身（[frame]）を作り直す。
 * いつ何を訳すか・何を捨てるか・ストリームを開き直すかは [CameraSession] が決める。
 *
 * 状態はすべてメインスレッドで触る。
 */
class LiveTranslator(private val context: Context, private val scope: CoroutineScope) {
    // 1行ずつ読む（段落にまとめると読み揺れで揃わない）。漫画モデルは縦書きの漫画用で遅いので使わない
    private val ocr = MultiScriptOcr(paddleDir = null)
    private val _frame = MutableStateFlow<LiveFrame?>(null)
    val frame: StateFlow<LiveFrame?> = _frame.asStateFlow()

    private val session = CameraSession<BoxColors>(lineOf = { text, box -> textLine(text, box.toRect()) })

    /** 今読んで制御へ渡しているコマ（[Effect.ShowFrame] で画面に出す）。init より前に置く。 */
    private var reading: LiveFrame? = null

    /** 中核の制御の入口（作業の中で出た出来事は、今の作業を終えてから渡す。[EventLoop]）。init より前に置く。 */
    private val loop = EventLoop<Event<BoxColors>, Effect>(session::handle, ::perform)

    /** 今の翻訳エンジン（切り替わったかを見分ける）。 */
    private var engine: TranslationEngine? = null

    /** 訳すストリームと、その結果の購読。 */
    private var stream: TranslationStream? = null
    private var streamCollector: Job? = null

    /**
     * 訳し方（原文の言語・訳す先・モード）。作るときは [Settings.of] で。変えると中核の制御に渡す（ストリームを開き直すかは
     * [CameraSession] が決める）。原文の言語は動かす認識器も決め（overlay/SourceLanguages.kt）、訳す先は訳す必要があるかの判定
     * （selectTargets）にも使う。
     */
    var settings: Settings = Settings.of(null, Language.JA, table = false)
        set(value) {
            // 中核の制御と同じ規則で正した値を持つ（認識器と訳す必要があるかの判定も、正した値で決める）
            val normalized = Settings.of(value.source, value.target, value.table)
            if (field != normalized) {
                field = normalized
                settingsChanged()
            }
        }

    /** 動かす認識器。原文の言語から決める。 */
    private val recognizers: OcrLanguage get() = settings.source.recognizers

    /** 一時停止中か（正本は [CameraSession.paused]。中核の制御が状態を変えるたびに写す）。画面の表示とコマを読むかに使う。 */
    private val _paused = MutableStateFlow(false)
    val paused: StateFlow<Boolean> = _paused.asStateFlow()

    /** 一時停止する・再開する。一時停止は読むのを止めて見えている段落を固定するだけで、訳し方は変えない（[CameraSession]）。 */
    fun setPaused(value: Boolean) {
        if (value && !session.paused) {
            pausedAt = SystemClock.uptimeMillis()
            val s = session.stats()
            Logs.i("カメラ翻訳: 一時停止（見えている訳待ち ${s.waitingOnScreen}件、訳している途中 ${s.inFlight}件、" +
                "ここまでに渡した ${s.submitted}件・うち訳し直し ${s.resubmitted}件、読めた行 ${s.read}件・うちぶれて軽くした ${s.blurred}件）")
        }
        if (!value) pausedAt = null
        dispatch(if (value) Event.Paused else Event.Resumed)
    }

    /** 一時停止した時刻（見えている訳がそろうまでの時間をログに出すため）。 */
    private var pausedAt: Long? = null

    /** 画面（映像の表示）の大きさ。画面に見えている範囲の計算に使う。呼ぶ側が表示の大きさが決まったら入れる。 */
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0


    init {
        settingsChanged()
        // 翻訳モデルの読み込み・切り替えと、同時に訳す数の変化を1本の購読で受け、出来事にする（ストリームを開き直すかは
        // CameraSession が決める）。ストリームに一度に渡しておく数は、同時に訳す数から `intakeFor` で決まる
        scope.launch {
            EngineHolder.awaitEngine(context)
            combine(EngineHolder.state, EngineHolder.parallel) { st, _ -> st as? ModelState.Ready }
                .filterNotNull()
                .map { Triple(it.engine, intakeFor(it.engine.parallel), it.model.origin.quality) }
                .distinctUntilChanged { a, b -> a.first === b.first && a.second == b.second }
                .collect { (e, intake, quality) ->
                    val changed = e !== engine
                    engine = e
                    dispatch(if (changed) Event.EngineChanged(intake, quality) else Event.IntakeChanged(intake))
                }
        }
    }

    private fun settingsChanged() {
        dispatch(Event.SettingsChanged(settings))
    }

    /**
     * 1コマを読む。終わるまで次のコマは渡さないこと（呼ぶ側で間引く）。
     * [bitmap] は縦持ちの向きの画像、[rotation] は端末の傾き（時計回り、0・90・180・270）。
     */
    suspend fun process(bitmap: Bitmap, rotation: Int = 0) {
        val (readings, ocrMillis) = read(bitmap, rotation) ?: return
        val screen = CameraGeometry.visibleRect(bitmap.width, bitmap.height, rotation, viewWidth.toFloat(), viewHeight.toFloat())
            ?.let { Box(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }
        // このコマを画面に出すかは中核の制御が決める（読んでいる間に一時停止したら採らない。採ったら ShowFrame）
        reading = LiveFrame(bitmap, emptyList(), ocrMillis, rotation, table = settings.table)
        try {
            dispatch(Event.Read(readings, screen, rotation = rotation))
        } finally {
            reading = null
        }
    }


    /** 縦持ちの画像を正立させて読み、訳す行の読み取りと、かかった時間を返す。読めなければ null。 */
    private suspend fun read(bitmap: Bitmap, rotation: Int): Pair<List<Reading<BoxColors>>, Long>? {
        val upright = if (rotation == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, CameraGeometry.portraitToUpright(bitmap.width, bitmap.height, rotation), false,
        )
        val t0 = SystemClock.uptimeMillis()
        return try {
            // カメラでは1文字だけの読み取りを捨てる（韓国語のラベルを中国語の認識器が読み違えた「太」「我」などの雑音が多い）
            val targets = selectTargets(ocr.recognize(upright, recognizers).map(TextBlock::of), upright, statusBarHeight = 0, target = settings.target)
                .filter { t -> t.sourceText.count { it.isLetter() } >= 2 }
            // 鋭さは行の数だけ画素を回るので、画面のスレッドを塞がない
            val readings = withContext(Dispatchers.Default) {
                targets.map { Reading(it.block.bounds.toBox(), it.sourceText, it.slot.colors, sharpness(upright, it.block.bounds)) }
            }
            readings to SystemClock.uptimeMillis() - t0
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Logs.w("カメラ翻訳: 文字認識に失敗", e)
            null
        } finally {
            if (upright !== bitmap) upright.recycle()
        }
    }

    /** 行の範囲の画像の鋭さ（[Sharpness]。手ぶれ・ピンぼけのコマの読み取りを、多数決で軽くするため）。 */
    private fun sharpness(image: Bitmap, bounds: Rect): Double? {
        val r = Rect(bounds)
        if (!r.intersect(0, 0, image.width, image.height)) return null
        val pixels = IntArray(r.width() * r.height())
        image.getPixels(pixels, 0, r.width(), r.left, r.top, r.width(), r.height())
        return Sharpness.of(pixels, r.width(), r.height())
    }

    /** 止めた画像を読み直す（一時停止中に原文の言語を変えたとき）。 */
    private suspend fun readStill() {
        val f = _frame.value ?: return
        val (readings, _) = read(f.bitmap, f.rotation) ?: return
        dispatch(Event.Read(readings, screen = null, still = true))
    }

    fun close() {
        stream?.close()
        streamCollector?.cancel()
        ocr.close()
    }

    private fun boxes(): List<LiveBox> = session.boxes().map {
        LiveBox(it.box.toRect(), it.source, it.look ?: BoxColors.DEFAULT, it.translation, it.onScreen)
    }

    /** 今のコマの枠に、訳の進み具合を入れ直す。 */
    private fun refresh() {
        val f = _frame.value ?: return
        _frame.value = f.with(boxes(), table = settings.table)
    }

    /** 出来事を中核の制御へ順に渡し、一時停止と枠を写し直す。 */
    private fun dispatch(event: Event<BoxColors>) {
        loop.dispatch(event)
        _paused.value = session.paused
        refresh()
    }

    /** 中核の制御が返した作業を1つ行う。 */
    private fun perform(e: Effect) {
        when (e) {
            is Effect.OpenStream -> openStream(e.options)
            is Effect.Submit -> stream?.submit(e.segments)
            Effect.ReadStill -> scope.launch { readStill() }
            Effect.VisibleComplete -> pausedAt?.let {
                Logs.i("カメラ翻訳: 一時停止から見えている訳がそろうまで ${SystemClock.uptimeMillis() - it}ms")
                pausedAt = null
            }
            // Read はその場で処理する（process から直接渡す）ので、読んでいるコマが必ずある
            Effect.ShowFrame -> _frame.value = checkNotNull(reading) { "読んでいるコマが無いのにコマを出す作業が来た" }
        }
    }

    /** 前のストリームを閉じ、[options] で翻訳のストリームを開き直す。渡していた文はストリームと一緒に消える。 */
    private fun openStream(options: TranslationStream.Options) {
        stream?.close()
        streamCollector?.cancel()
        val s = Translations.open(scope, context, options)
        stream = s
        streamCollector = scope.launch { s.updates.collect(::onUpdate) }
        dispatch(Event.StreamOpened)
    }

    private fun onUpdate(u: SegmentUpdate) {
        val event = when (u) {
            is SegmentUpdate.Partial -> Event.Partial(u.id, u.text)
            is SegmentUpdate.Final -> {
                Logs.i("カメラ翻訳: 1件を${u.millis}ms${if (u.cached) "（キャッシュ）" else ""}")
                Event.Final(u.id, u.text)
            }
            is SegmentUpdate.Failed -> {
                Logs.w("カメラ翻訳: 翻訳に失敗 ${u.message}")
                Event.Failed(u.id)
            }
        }
        dispatch(event)
    }
}
