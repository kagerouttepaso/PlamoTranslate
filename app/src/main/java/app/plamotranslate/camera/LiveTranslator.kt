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
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.overlay.textLine
import app.plamotranslate.overlay.toBox
import app.plamotranslate.overlay.toRect
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
 * カメラ翻訳の外とつなぐ部分（docs/設計/カメラ翻訳.md）。コマを読み、端末の出来事を中核の制御（[CameraSession]）へ渡し、
 * 返ってきた作業（翻訳の流れを開く・文を渡す・止めた画像を読み直す）を行い、描く中身（[frame]）を作り直す。
 * いつ何を訳すか・何を捨てるか・流れを開き直すかは [CameraSession] が決める。
 *
 * 状態はすべてメインスレッドで触る。
 */
class LiveTranslator(private val context: Context, private val scope: CoroutineScope) {
    // 1行ずつ読む（段落にまとめると読み揺れで揃わない）。漫画モデルは縦書きの漫画用で遅いので使わない
    private val ocr = MultiScriptOcr(paddleDir = null)
    private val _frame = MutableStateFlow<LiveFrame?>(null)
    val frame: StateFlow<LiveFrame?> = _frame.asStateFlow()

    private val session = CameraSession<BoxColors>(lineOf = { text, box -> textLine(text, box.toRect()) })

    /** 今の翻訳エンジンと、流れに一度に渡しておく数（同時に訳す数の設定から `intakeFor` で決まる）。 */
    private var engine: TranslationEngine? = null
    private var intake = 0

    /** 訳す流れと、その結果の購読。 */
    private var stream: TranslationStream? = null
    private var streamCollector: Job? = null

    /** 原文の言語。null は自動（すべての認識器で読み、PLaMoが原文の言語を判定する）。 */
    var source: Language? = null
        set(value) {
            if (field != value) {
                field = value
                settingsChanged()
            }
        }

    /** 訳す先の言語（2026-11-08、作者の要望で日本語以外も選べるようにした）。
     * 訳す必要があるかの判定（selectTargets）にも使う。訳す先を日本語以外にすると、日本語の文字も訳す。 */
    var target: Language = Language.JA
        set(value) {
            if (field != value) {
                field = value
                settingsChanged()
            }
        }

    /** 動かす認識器。原文の言語から決める（CameraLanguage.kt）。 */
    private val recognizers: OcrLanguage get() = source?.ocrLanguage ?: OcrLanguage.AUTO

    /** 文章のモード（行を段落にまとめる）か、表・メニューのモード（1行ずつ）か。 */
    var groupParagraphs = true
        set(value) {
            if (field != value) {
                field = value
                settingsChanged()
            }
        }

    /** 一時停止中か。一時停止は読むのを止めて見えている段落を固定するだけで、訳し方は変えない（[CameraSession]）。 */
    var paused = false
        set(value) {
            if (field == value) return
            field = value
            pausedAt = if (value) SystemClock.uptimeMillis() else null
            if (value) Logs.i("カメラ翻訳: 一時停止（見えている訳待ち ${session.waitingOnScreen()}件、訳している途中 ${session.inFlightCount}件、" +
                "ここまでに渡した ${session.submittedCount}件・うち訳し直し ${session.resubmittedCount}件、" +
                "読めた行 ${session.readCount}件・うちぶれて軽くした ${session.blurredCount}件、" +
                "鋭さの割合の分布 ${session.sharpnessRatios().joinToString("/")}（0.1刻み））")
            apply(session.handle(if (value) Event.Paused else Event.Resumed))
        }

    /** 一時停止した時刻（見えている訳がそろうまでの時間をログに出すため）。 */
    private var pausedAt: Long? = null

    /** 画面（映像の表示）の大きさ。画面に見えている範囲の計算に使う。呼ぶ側が表示の大きさが決まったら入れる。 */
    @Volatile var viewWidth = 0
    @Volatile var viewHeight = 0

    private var rotation = 0

    init {
        settingsChanged()
        // 翻訳モデルが読み込まれたら・切り替わったら、知らせる（流れを開き直すかは CameraSession が決める）
        scope.launch {
            EngineHolder.awaitEngine(context)
            EngineHolder.state.collect { st ->
                if (st is ModelState.Ready && st.engine !== engine) {
                    engine = st.engine
                    intake = intakeFor(st.engine.parallel)
                    apply(session.handle(Event.EngineChanged(intake)))
                }
            }
        }
    }

    private fun settingsChanged() {
        apply(session.handle(Event.SettingsChanged(Settings(source, target, table = !groupParagraphs))))
    }

    /**
     * 1コマを読む。終わるまで次のコマは渡さないこと（呼ぶ側で間引く）。
     * [bitmap] は縦持ちの向きの画像、[rotation] は端末の傾き（時計回り、0・90・180・270）。
     */
    suspend fun process(bitmap: Bitmap, rotation: Int = 0) {
        // 向きが変わったら座標の向きも変わるので、追っていた行は捨てる
        if (rotation != this.rotation) {
            this.rotation = rotation
            apply(session.handle(Event.Rotated))
        }
        // 同時に訳す数は設定の画面で変わる（エンジンはそのまま）
        engine?.let { e -> intakeFor(e.parallel).takeIf { it != intake }?.let { intake = it; apply(session.handle(Event.IntakeChanged(it))) } }
        val (readings, ocrMillis) = read(bitmap, rotation) ?: return
        if (paused) return
        val screen = CameraGeometry.visibleRect(bitmap.width, bitmap.height, rotation, viewWidth.toFloat(), viewHeight.toFloat())
            ?.let { Box(floor(it.left).toInt(), floor(it.top).toInt(), ceil(it.right).toInt(), ceil(it.bottom).toInt()) }
        val effects = session.handle(Event.Read(readings, screen))
        _frame.value = LiveFrame(bitmap, boxes(), ocrMillis, rotation, table = !groupParagraphs)
        apply(effects)
    }

    /** 縦持ちの画像を正立させて読み、訳す行の読み取りと、かかった時間を返す。読めなければ null。 */
    private suspend fun read(bitmap: Bitmap, rotation: Int): Pair<List<Reading<BoxColors>>, Long>? {
        val upright = if (rotation == 0) bitmap else Bitmap.createBitmap(
            bitmap, 0, 0, bitmap.width, bitmap.height, CameraGeometry.portraitToUpright(bitmap.width, bitmap.height, rotation), false,
        )
        val t0 = SystemClock.uptimeMillis()
        return try {
            // カメラでは1文字だけの読み取りを捨てる（韓国語のラベルを中国語の認識器が読み違えた「太」「我」などの雑音が多い）
            val targets = selectTargets(ocr.recognize(upright, recognizers).map(TextBlock::of), upright, statusBarHeight = 0, target = target)
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
        apply(session.handle(Event.Read(readings, screen = null, still = true)))
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
        _frame.value = f.with(boxes(), table = !groupParagraphs)
    }

    /** 中核の制御が返した作業を行い、枠を描き直す。 */
    private fun apply(effects: List<Effect>) {
        for (e in effects) when (e) {
            is Effect.OpenStream -> openStream(e.options)
            is Effect.Submit -> stream?.submit(e.segments)
            Effect.ReadStill -> scope.launch { readStill() }
            Effect.VisibleComplete -> pausedAt?.let {
                Logs.i("カメラ翻訳: 一時停止から見えている訳がそろうまで ${SystemClock.uptimeMillis() - it}ms")
                pausedAt = null
            }
        }
        refresh()
    }

    /** 前の流れを閉じ、[options] で翻訳の流れを開き直す。渡していた文は流れと一緒に消える。 */
    private fun openStream(options: TranslationStream.Options) {
        stream?.close()
        streamCollector?.cancel()
        val s = Translations.open(scope, context, options)
        stream = s
        streamCollector = scope.launch { s.updates.collect(::onUpdate) }
        apply(session.handle(Event.StreamOpened))
    }

    private fun onUpdate(u: SegmentUpdate) {
        val event = when (u) {
            is SegmentUpdate.Partial -> Event.Partial(u.id, u.text)
            is SegmentUpdate.Final -> {
                Logs.i("カメラ翻訳: 1件を${u.millis}ms${if (u.cached) "（控え）" else ""}")
                Event.Final(u.id, u.text)
            }
            is SegmentUpdate.Failed -> {
                Logs.w("カメラ翻訳: 翻訳に失敗 ${u.message}")
                Event.Failed(u.id)
            }
        }
        apply(session.handle(event))
    }
}
