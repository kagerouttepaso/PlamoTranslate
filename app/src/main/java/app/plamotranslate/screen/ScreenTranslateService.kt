package app.plamotranslate.screen

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.translation.SqlitePersistentCache
import app.plamotranslate.overlay.TranslationTarget
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.pipeline.screen.SceneProfile
import app.plamotranslate.pipeline.screen.ScreenChange
import app.plamotranslate.pipeline.screen.ScreenSession
import app.plamotranslate.pipeline.screen.ScreenSession.Effect
import app.plamotranslate.pipeline.screen.ScreenSession.Phase
import app.plamotranslate.pipeline.screen.ScreenSession.SessionEvent
import app.plamotranslate.pipeline.screen.StartJudge
import app.plamotranslate.pipeline.screen.Thumb
import app.plamotranslate.pipeline.screen.Validity
import app.plamotranslate.pipeline.screen.ValidityJudge
import app.plamotranslate.pipeline.translation.MemoryCache
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.translation.Translations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * 画面翻訳のフォアグラウンドサービス（docs/設計/画面翻訳.md、構成は docs/設計/画面翻訳.md）。
 *
 * このサービスは部品をつなぐだけで、何をするかは決めない。
 * - 制御：画面翻訳の状態遷移（中核の [ScreenSession]）が、出来事を受け取って次の状態と作業を決める（[dispatch]）。
 * - 作業の実行：状態遷移が返した作業（[Effect]）を、端末の部品（撮影・文字認識・翻訳の流れ・訳の枠）で行い、結果をセッション番号つきの
 *   出来事として状態遷移へ返す（[perform]）。
 * - 見張り：届いた画面を、待ちの間は開始の判定A（[StartJudge]）へ、訳している間と表示している間は有効性の判定B（[ValidityJudge]）へ渡す（[watch]）。
 * - 合図：浮遊ボタン・メニュー・ほかのアプリに触れた・ユーザー補助の画面の切り替わり・画面の回転を、出来事にして状態遷移へ渡す。
 *
 * 状態・判定器・作業の材料は、すべてメインスレッドで触る。
 */
class ScreenTranslateService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var projection: MediaProjection? = null
    private var capturer: ScreenCapturer? = null
    private var button: FloatingButton? = null
    private var overlay: TranslationOverlay? = null
    private var menu: ScreenMenu? = null
    private var touchWatcher: TouchWatcher? = null
    private val preferences by lazy { ScreenPreferences(this) }
    private val ocr by lazy { MultiScriptOcr(ModelCatalog.ocrDir(this)) }

    /** 翻訳のメモリの控え。1回の翻訳ごとに開く翻訳の流れで共有する（訳したモデルが変わったら捨てる）。 */
    private val memoryCache = MemoryCache()
    private var debugReceiver: BroadcastReceiver? = null
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    /** 画面翻訳の状態（中核の状態遷移が決める）。 */
    private var state = ScreenSession.initial(ScreenSession.Trigger.TAP)

    /** 場面の設定と、それに合わせた判定器。設定を変えたら作り直す（[applyProfile]）。 */
    private var profile = SceneProfile.NORMAL
    private var startJudge = StartJudge(profile.start)
    private var validity = ValidityJudge(profile.validity)

    /** 1回の翻訳（セッション）の作業と材料。状態遷移は番号だけを持ち、材料はここが持つ。 */
    private class Work(val session: Int) {
        val job: CompletableJob = SupervisorJob()
        var bitmap: Bitmap? = null
        var reference: Thumb? = null
        var frames = 1
        var captureMillis = 0L
        var ocrMillis = 0L
        var ocrError: String? = null
        var blocks = 0
        var targets: List<TranslationTarget> = emptyList()
        var statusBar = 0
        var capturedSize = ""
        var translateStart = 0L
        var model = ""
        var stats: TranslationStream.Stats? = null
        var peekJob: Job? = null
    }

    private var work: Work? = null

    /** 撮る・のぞくために自分の表示を隠している間。見張りはこの間の画面を判定器へ渡さない。 */
    private var hiding = false
    private var modelJob: Job? = null

    /** メニューを出している間だけ、翻訳モデルの状態を購読する。 */
    private var menuJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /** 画面を回したら、前の向きで置いた訳の枠は位置が合わないので消す。浮遊ボタンは今の画面の同じ割合の位置へ移す。 */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == orientation) return
        orientation = newConfig.orientation
        menu?.dismiss()
        button?.fitToScreen()
        dispatch(SessionEvent.Rotated)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (projection != null) return START_NOT_STICKY

        // Android 14以降は、getMediaProjection より前にフォアグラウンドへ上げておく必要がある
        startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val mp = data?.let { getSystemService(MediaProjectionManager::class.java).getMediaProjection(resultCode, it) }
        if (mp == null) {
            Toast.makeText(this, "画面の撮影を開始できませんでした", Toast.LENGTH_SHORT).show()
            stopSelf()
            return START_NOT_STICKY
        }
        // Android 14以降は createVirtualDisplay の前にコールバックの登録が必須
        mp.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() = stopSelf()
        }, Handler(Looper.getMainLooper()))
        projection = mp
        capturer = ScreenCapturer(this, mp)
        orientation = resources.configuration.orientation

        val wm = getSystemService(WindowManager::class.java)
        overlay = TranslationOverlay(this, wm).also {
            it.onStatusTap = { dispatch(SessionEvent.StatusTapped) }
            it.opaqueWithAccessibility = { preferences.opaqueOverlay }
        }
        // ユーザー補助がつながっていれば、ほかのアプリ・画面への切り替わりを合図にする（docs/資料/端末の能力.md「問い1」）
        OverlayAccessibilityService.onWindowChanged = ::onWindowChanged
        OverlayAccessibilityService.onScrolled = ::onScrolled
        touchWatcher = TouchWatcher(this, wm, ::onScreenTouch).also { it.show() }
        menu = ScreenMenu(this, wm).also {
            it.onDismiss = {
                menuJob?.cancel()
                dispatch(SessionEvent.MenuClosed)
            }
        }
        button = FloatingButton(this, wm, onTap = ::onButtonTap, onLongPress = ::showMenu).also {
            it.setLabel(buttonLabel(preferences.language, preferences.trigger))
            it.setActive(true)
            it.show()
        }
        EngineHolder.ensureLoaded(this)
        applyProfile()
        state = ScreenSession.initial(preferences.trigger.toTrigger())
        if (state.phase == Phase.Watching) startJudge.reset(SystemClock.uptimeMillis())
        scope.launch { watch() }
        scope.launch { watchAccessibility() }
        registerDebugReceiver()
        _running.value = true
        return START_NOT_STICKY
    }

    // ---- 制御：出来事を状態遷移へ渡し、返った作業を行う ----

    private fun dispatch(event: SessionEvent) {
        val (next, effects) = ScreenSession.reduce(state, event)
        state = next
        effects.forEach(::perform)
    }

    private fun perform(effect: Effect) {
        when (effect) {
            is Effect.Capture -> capture(effect.session)
            is Effect.BeginValidity -> workOf(effect.session)?.reference?.let(validity::begin)
            is Effect.Recognize -> recognize(effect.session)
            is Effect.NothingToTranslate -> workOf(effect.session)?.let { w ->
                overlay?.showStatus(w.ocrError ?: "訳す文字が見つかりませんでした（文字認識 ${w.ocrMillis}ms）")
            }
            is Effect.Translate -> translate(effect.session)
            is Effect.Finished -> workOf(effect.session)?.let(::finished)
            is Effect.Failed -> overlay?.showStatus(if (effect.message.startsWith("画面")) effect.message else "翻訳に失敗しました: ${effect.message}")
            is Effect.Peek -> peek(effect.session)
            is Effect.Cancel -> cancel(effect.session)
            Effect.ClearOverlay -> overlay?.clear()
            Effect.EndValidity -> validity.end()
            Effect.ResetStart -> startJudge.reset(SystemClock.uptimeMillis())
            is Effect.ButtonActive -> button?.setActive(effect.active)
            is Effect.Notice -> Toast.makeText(this, effect.text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun workOf(session: Int): Work? = work?.takeIf { it.session == session }

    /** セッションの作業（撮影・文字認識・翻訳・のぞき）を取り消し、材料を捨てる。 */
    private fun cancel(session: Int) {
        val w = workOf(session) ?: return
        w.job.cancel()
        w.bitmap?.recycle()
        w.bitmap = null
        work = null
        hiding = false
        button?.setVisible(true)
    }

    // ---- 作業の実行 ----

    /** 自分の表示を隠して画面を撮る。場面の設定が数枚を重ねるなら、続けて撮って中央値の1枚にする（[FrameStack]）。 */
    private fun capture(session: Int) {
        val w = Work(session).also { work = it }
        val capturer = capturer ?: return dispatch(SessionEvent.Captured(session, ok = false))
        val profile = profile
        scope.launch(w.job) {
            val hiddenAt = SystemClock.uptimeMillis()
            hiding = true
            val captured = try {
                button?.setVisible(false)
                overlay?.clear()
                delay(HIDE_SETTLE_MS)
                // 消した後に描かれた画面（1フレーム＝約16ms後以降に届いたもの）を使う
                captureScreen(capturer, since = hiddenAt + 16, profile)
            } finally {
                button?.setVisible(true)
                hiding = false
            }
            w.captureMillis = SystemClock.uptimeMillis() - hiddenAt
            if (captured != null) {
                w.bitmap = captured.first
                w.frames = captured.second
                w.capturedSize = "${captured.first.width}x${captured.first.height}"
                w.reference = captured.first.toThumb()
            }
            dispatch(SessionEvent.Captured(session, ok = captured != null))
        }
    }

    private suspend fun captureScreen(capturer: ScreenCapturer, since: Long, profile: SceneProfile): Pair<Bitmap, Int>? {
        if (profile.stackFrames <= 1) return capturer.capture(since)?.let { it to 1 }
        val frames = capturer.captureFrames(since, profile.stackFrames, profile.stackIntervalMs)
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0] to 1
        val stacked = try {
            FrameStack.median(frames)
        } finally {
            frames.forEach { it.recycle() }
        }
        return stacked to frames.size
    }

    /** 文字を読み、訳す対象と枠の位置・色を選ぶ。 */
    private fun recognize(session: Int) {
        val w = workOf(session) ?: return
        val bitmap = w.bitmap ?: return
        val language = preferences.language
        val outlined = profile.outlinedText
        scope.launch(w.job) {
            overlay?.showStatus("文字を読んでいます…")
            val t0 = SystemClock.uptimeMillis()
            val blocks = try {
                // 白縁取り・白抜きの文字にも強い読み方（OutlinedText）は、場面の設定で選ぶ
                if (outlined) ocr.recognizeOutlined(bitmap, language) else ocr.recognize(bitmap, language)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                w.ocrError = "文字を読めませんでした: ${e.message}"
                emptyList()
            }
            w.ocrMillis = SystemClock.uptimeMillis() - t0
            w.blocks = blocks.size
            w.statusBar = statusBarHeight()
            w.targets = if (blocks.isEmpty()) emptyList() else selectTargets(blocks, bitmap, w.statusBar, SCREEN_TARGET, fitBubbles = preferences.fitBubbles)
            bitmap.recycle()
            w.bitmap = null
            dispatch(SessionEvent.Recognized(session, w.targets.size))
        }
    }

    /** 空の訳の枠を出し、翻訳の流れを開いて訳を流し込む。 */
    private fun translate(session: Int) {
        val w = workOf(session) ?: return
        overlay?.show(w.targets.map { it.slot }, "${w.targets.size}件を翻訳中…")
        val source = preferences.language.sourceLanguage
        scope.launch(w.job) {
            val ready = EngineHolder.awaitReady(this@ScreenTranslateService)
            if (ready == null) {
                dispatch(SessionEvent.TranslationFailed(session, "翻訳モデルを読めません。アプリを開いて確認してください"))
                return@launch
            }
            w.model = ready.model.label
            w.translateStart = SystemClock.uptimeMillis()
            // 1回の翻訳ごとに翻訳の流れを開き、終わったら閉じる（docs/設計/翻訳コア.md「翻訳の契約」）。
            // メモリの控えはサービスが持って翻訳どうしで共有し、永続の控え（端末のデータベース）で次に始めたときも使う
            val persistent = SqlitePersistentCache.get(this@ScreenTranslateService)
            val stream = Translations.open(this, this@ScreenTranslateService, TranslationStream.Options(source, SCREEN_TARGET), memoryCache, persistent)
            try {
                stream.submit(w.targets.mapIndexed { i, t -> Segment(i.toLong(), t.sourceText) })
                stream.updates.transformWhile { u ->
                    when (u) {
                        is SegmentUpdate.Partial -> overlay?.setTranslation(u.id.toInt(), u.text)
                        is SegmentUpdate.Final -> {
                            overlay?.setTranslation(u.id.toInt(), u.text)
                            // 最後の確定で状態遷移が Finished を返し、その場で記録する（finished）。それより前に控えの数を渡す
                            w.stats = stream.stats
                            dispatch(SessionEvent.SegmentDone(session))
                        }
                        is SegmentUpdate.Failed -> dispatch(SessionEvent.TranslationFailed(session, u.message))
                    }
                    emit(u)
                    // 全部確定した・失敗した・取り消した：状態遷移がこのセッションの翻訳を終えたら読むのをやめる
                    state.phase.let { it is Phase.Translating && it.session == session }
                }.collect { }
            } finally {
                w.stats = stream.stats
                stream.close()
            }
        }
    }

    /** 全部の訳が確定した：上部に件数と時間を出し、記録する。 */
    private fun finished(w: Work) {
        val translateMillis = SystemClock.uptimeMillis() - w.translateStart
        // 数枚を撮って重ねるときは、撮る時間も待ち時間なので含める
        val total = w.ocrMillis + translateMillis + if (w.frames > 1) w.captureMillis else 0
        val frames = if (w.frames > 1) "・${w.frames}枚重ね" else ""
        overlay?.showStatus("${w.targets.size}件・${"%.1f".format(total / 1000.0)}秒・${w.model}$frames")
        val st = w.stats
        Log.i(
            TAG,
            "画面翻訳: ${w.blocks}ブロック中${w.targets.size}件、撮影 ${w.captureMillis}ms（${w.frames}枚）、文字認識 ${w.ocrMillis}ms、" +
                "翻訳 ${translateMillis}ms、モデル ${w.model}、重要度 ${importance()}、画面 ${w.capturedSize}、状態バー ${w.statusBar}px" +
                (st?.let { "、控え メモリ${it.memoryHits}・データベース${it.storedHits}、訳した${it.pieces}片（訳し直し${it.retried}、打ち切り${it.stopped}）" } ?: ""),
        )
    }

    /**
     * 自動で訳を出しているときに触れたら、触れた操作に画面が応えるのを待ってから、訳の枠を隠して枠の下を撮り、
     * 判定Bで原文が変わったかを見る（ゲームの台詞送り：変わるのは訳の枠の真下だけで、枠の外では気づけない）。
     */
    private fun peek(session: Int) {
        val w = workOf(session) ?: return
        if (w.peekJob?.isActive == true) return
        val capturer = capturer ?: return
        val frames = profile.validity.peekFrames
        val interval = profile.validity.peekIntervalMs
        w.peekJob = scope.launch(w.job) {
            delay(PEEK_DELAY_MS)
            if (state.phase != Phase.Showing(session) || state.menuOpen) return@launch
            val overlay = overlay ?: return@launch
            val boxes = overlay.boxBounds.takeIf { it.isNotEmpty() } ?: return@launch
            hiding = true
            val thumbs = mutableListOf<Thumb>()
            try {
                val hiddenAt = SystemClock.uptimeMillis()
                overlay.setBoxesVisible(false)
                delay(HIDE_SETTLE_MS)
                // 枠を隠した後の画面を撮る。枚数と間隔は場面の設定（動く画面は3枚の中央値で背景の動きを消す）
                var after = hiddenAt + 16
                val deadline = SystemClock.uptimeMillis() + PEEK_TIMEOUT_MS
                while (thumbs.size < frames && SystemClock.uptimeMillis() < deadline) {
                    capturer.latestThumb(after)?.let { (t, at) ->
                        thumbs += t
                        after = at + interval
                    }
                    delay(16)
                }
            } finally {
                hiding = false
            }
            val verdict = if (thumbs.isEmpty()) Validity.Valid else {
                validity.peek(if (thumbs.size >= 3) ScreenChange.median(thumbs) else thumbs.last(), boxes.map { it.toBox() })
            }
            Log.i(TAG, "自動翻訳: 触れたので訳の枠の下を見た（$verdict）")
            if (verdict is Validity.Invalid) dispatch(SessionEvent.Invalidated(verdict.reason)) else overlay.setBoxesVisible(true)
        }
    }

    // ---- 見張り：届いた画面を判定器へ ----

    /**
     * [AUTO_POLL_MS] ごとに新しく届いた画面を格子に縮め（[ScreenCapturer.latestThumb]）、待ちの間は開始の判定Aへ、
     * 訳している間と表示している間は有効性の判定Bへ渡す。判定の結果は出来事にして状態遷移へ渡す。
     */
    private suspend fun watch() {
        var lastAt = 0L
        while (true) {
            delay(AUTO_POLL_MS)
            val capturer = capturer ?: return
            // 撮る・のぞくために自分の表示を隠している間と、メニューを選んでいる間は判定しない
            if (hiding || state.menuOpen) continue
            val sample = capturer.latestThumb(lastAt)
            val now = SystemClock.uptimeMillis()
            if (sample != null) lastAt = sample.second
            val thumb = sample?.first
            when {
                state.phase == Phase.Watching -> {
                    val own = thumb?.let { t -> ScreenChange.mask(t, listOfNotNull(button?.bounds?.toBox())) }
                    if (startJudge.feed(thumb, now, own)) dispatch(SessionEvent.Settled)
                }
                validity.active && thumb != null -> {
                    val own = overlay?.occupiedRects().orEmpty().map { it.toBox() } + listOfNotNull(button?.bounds?.toBox())
                    val boxes = overlay?.boxBounds.orEmpty().map { it.toBox() }
                    val verdict = validity.feed(thumb, boxes, own, watchOutside = state.trigger == ScreenSession.Trigger.AUTO)
                    if (verdict is Validity.Invalid) {
                        Log.i(TAG, "画面翻訳: ${verdict.reason}（${"%.2f".format(verdict.amount)}）ので取り消した")
                        dispatch(SessionEvent.Invalidated(verdict.reason))
                    }
                }
            }
        }
    }

    // ---- 合図 ----

    /** 浮遊ボタンのタップ。タップで訳すときは訳す・消す、自動で訳すときは見張りを止める・再開する（状態遷移が決める）。 */
    private fun onButtonTap() {
        menu?.dismiss()
        dispatch(SessionEvent.ButtonTapped)
    }

    /** ほかのアプリの画面に触れたとき（[TouchWatcher]。自分のボタン・メニュー・表示への操作は除いてある）。 */
    private fun onScreenTouch() {
        if (state.working) Log.i(TAG, "画面翻訳: 訳している途中に画面を操作したので取り消した")
        dispatch(SessionEvent.OutsideTouched)
    }

    /** ほかのアプリ・画面への切り替わり（ユーザー補助の知らせ。アプリの切り替え・ホーム・最近使ったアプリ・別の画面）。 */
    private fun onWindowChanged() {
        if (state.session == null) return
        Log.i(TAG, "画面翻訳: ほかのアプリの画面が切り替わったので取り消した")
        dispatch(SessionEvent.Invalidated("ほかの画面に切り替わった"))
    }

    /**
     * ほかのアプリの中身がスクロールした（ユーザー補助の知らせ）。原文が動いたので、訳している途中の処理と出している訳を取り消す。
     * ユーザー補助の知らせは約0.1秒間引いて届くので、通常のモードでは判定Bの「画面が変わった」が先に取り消すことが多い。
     * 判定Bが遅いとき（動く画面のモードは画面全体の変化を見ず、外周を3回の中央値で比べる）に先に効く予備の合図（2026-11-12に実機で確かめた）。
     */
    private fun onScrolled() {
        if (state.session == null) return
        Log.i(TAG, "画面翻訳: スクロールしたので取り消した")
        dispatch(SessionEvent.Invalidated("スクロールした"))
    }

    /**
     * ユーザー補助が「有効なのにつながっていない」（端末に止められて切り離された）ことを見つけて知らせる（docs/設計/画面翻訳.md
     * 「使える能力と組み立て」）。切り離されると、訳の枠は透ける重ね表示に、合図は画面の見張りだけになる。
     * 始めた直後はつながるまで少しかかるので、待ってから見る。その後は、つながりが切れたときに見る。
     */
    private suspend fun watchAccessibility() {
        delay(A11Y_CHECK_DELAY_MS)
        notifyIfDetached()
        // 今の値はすぐ上で見たので、変わったときだけ見る
        OverlayAccessibilityService.connected.drop(1).collect { connected -> if (!connected) notifyIfDetached() }
    }

    private fun notifyIfDetached() {
        if (!preferences.opaqueOverlay || !OverlayAccessibilityService.isDetached(this)) return
        Log.w(TAG, "画面翻訳: ユーザー補助が有効なのにつながっていない")
        val text = "ユーザー補助が切れています。ユーザー補助の設定で「PLaMo翻訳」を一度オフにしてからオンにしてください"
        overlay?.showStatus(text)
        Toast.makeText(this, text, Toast.LENGTH_LONG).show()
    }

    /** 場面の設定（認識モードと訳の消え方）を今の設定に合わせ、判定器を作り直す。 */
    private fun applyProfile() {
        profile = SceneProfile.of(
            motion = preferences.recognition == ScreenRecognition.MOTION,
            untilTouched = preferences.dismissal == ScreenDismissal.ON_TOUCH,
        )
        startJudge = StartJudge(profile.start).also { it.reset(SystemClock.uptimeMillis()) }
        validity = ValidityJudge(profile.validity)
    }

    /**
     * 長押しのメニュー：原文の言語、始め方、認識モード、訳の消え方、訳の枠、翻訳モデル、終了。
     * 翻訳モデルはアプリ全体で1つなので、ここで変えると会話・カメラ・テキストの画面のモデルも変わる。
     * 開くと訳を消し、訳している途中なら取り消す（訳の枠がメニューより手前に出て触れなくなるため。状態遷移の MenuOpened）。
     */
    private fun showMenu() {
        val (x, y) = button?.position ?: return
        dispatch(SessionEvent.MenuOpened)
        menu?.show(x, y, ::menuEntries)
        // 翻訳モデルの読み込みが進んだら、選べるモデルの印を描き直す（メニューは選んでも閉じないため）
        menuJob?.cancel()
        menuJob = scope.launch { EngineHolder.state.drop(1).collect { menu?.refresh() } }
    }

    /** メニューの行。選択肢を押すたびに作り直すので、今の設定を読んで作る。 */
    private fun menuEntries(): List<MenuEntry> = buildList {
        add(MenuEntry.Choice("原文の言語", OcrLanguage.entries.map { lang ->
            Option(lang.menuName, checked = lang == preferences.language) {
                preferences.language = lang
                button?.setLabel(buttonLabel(lang, preferences.trigger))
            }
        }))
        add(MenuEntry.Choice("始め方", ScreenTrigger.entries.map { t ->
            Option(t.menuName, checked = t == preferences.trigger) {
                preferences.trigger = t
                button?.setLabel(buttonLabel(preferences.language, t))
                dispatch(SessionEvent.TriggerChanged(t.toTrigger()))
            }
        }))
        add(MenuEntry.Choice("認識モード", ScreenRecognition.entries.map { mode ->
            Option(mode.menuName, checked = mode == preferences.recognition) {
                preferences.recognition = mode
                applyProfile()
            }
        }))
        add(MenuEntry.Choice("訳の消え方", ScreenDismissal.entries.map { d ->
            Option(d.menuName, checked = d == preferences.dismissal) {
                preferences.dismissal = d
                applyProfile()
            }
        }))
        add(MenuEntry.Choice("訳の枠", listOf(
            Option("文字に合わせる", checked = !preferences.fitBubbles) { preferences.fitBubbles = false },
            Option("吹き出しに広げる", checked = preferences.fitBubbles) { preferences.fitBubbles = true },
        )))
        val engineState = EngineHolder.state.value
        val current = (engineState as? ModelState.Ready)?.model ?: EngineHolder.selected(this@ScreenTranslateService)
        add(MenuEntry.Choice("翻訳モデル", TranslationModel.entries.map { m ->
            val present = m.isPresent(this@ScreenTranslateService)
            Option(
                m.label,
                checked = m == current,
                enabled = present && engineState !is ModelState.Loading,
                disabledHint = if (present) "翻訳モデルを読み込んでいます" else "${m.label}は設定の画面からダウンロードしてください",
            ) { if (m != current) switchModel(m) }
        }))
        add(MenuEntry.Action("画面翻訳を終了") { stopSelf() })
    }

    /** 翻訳モデルを切り替え、読み込みの進み具合を上部の小さな表示に出す。訳している途中なら取り消す（古いエンジンは閉じられる）。 */
    private fun switchModel(model: TranslationModel) {
        dispatch(SessionEvent.ModelChanged)
        EngineHolder.select(this, model)
        modelJob?.cancel()
        modelJob = scope.launch {
            overlay?.showStatus("${model.label}を読み込んでいます…")
            val ready = EngineHolder.awaitReady(this@ScreenTranslateService)
            overlay?.showStatus(if (ready != null) "翻訳モデルを${ready.model.label}にしました" else "翻訳モデルを読めませんでした")
        }
    }

    /**
     * デバッグビルドだけ、PCから adb で撮影と設定変更をできるようにする（速度の計測用）。
     * adb shell am broadcast -a app.plamotranslate.DEBUG_TRANSLATE
     * adb shell am broadcast -a app.plamotranslate.DEBUG_CONFIG --ez boost false --ei threads 6
     */
    private fun registerDebugReceiver() {
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    DEBUG_TRANSLATE -> dispatch(SessionEvent.ButtonTapped)
                    DEBUG_CONFIG -> scope.launch {
                        EngineHolder.awaitEngine(this@ScreenTranslateService)?.configure(
                            boost = intent.getBooleanExtra("boost", true),
                            threads = intent.getIntExtra("threads", 0),
                        )
                    }
                }
            }
        }
        registerReceiver(receiver, IntentFilter().apply {
            addAction(DEBUG_TRANSLATE)
            addAction(DEBUG_CONFIG)
        }, RECEIVER_EXPORTED)
        debugReceiver = receiver
    }

    override fun onDestroy() {
        debugReceiver?.let { unregisterReceiver(it) }
        OverlayAccessibilityService.onWindowChanged = null
        OverlayAccessibilityService.onScrolled = null
        work?.let { cancel(it.session) }
        scope.cancel()
        button?.remove()
        touchWatcher?.remove()
        overlay?.clear()
        menu?.onDismiss = null
        menu?.dismiss()
        capturer?.release()
        projection?.stop()
        // 撮影を始められなかったときは認識器を作っていないので、閉じない（lazy を初めて触ると作ってしまう）
        if (projection != null) ocr.close()
        projection = null
        _running.value = false
        super.onDestroy()
    }

    private fun buildNotification(): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ID, "画面翻訳", NotificationManager.IMPORTANCE_LOW))
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ScreenTranslateService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("画面翻訳中")
            .setContentText("浮遊ボタンで翻訳。長押しで言語の選択と終了")
            .addAction(Notification.Action.Builder(null, "終了", stop).build())
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "PlamoTranslate"
        private const val CHANNEL_ID = "screen_translate"
        private const val NOTIFICATION_ID = 1

        /** 自分の表示を隠してから、隠れた画面が描かれるのを待つ時間。 */
        private const val HIDE_SETTLE_MS = 50L

        /** 画面を見る間隔。縮めた格子を比べるだけなので軽い。 */
        private const val AUTO_POLL_MS = 200L

        /** 触れてから訳の枠の下をのぞくまでの待ち（台詞送りなど、触れた操作に画面が応えて描き終わるのを待つ）。 */
        private const val PEEK_DELAY_MS = 350L

        /** 訳の枠を隠してから、のぞく画面が届くのを待つ上限。 */
        private const val PEEK_TIMEOUT_MS = 600L

        /** 始めてから、ユーザー補助が切り離されていないかを見るまでの待ち（始めた直後はつながるまで少しかかる）。 */
        private const val A11Y_CHECK_DELAY_MS = 3000L

        const val EXTRA_RESULT_CODE = "resultCode"
        const val EXTRA_DATA = "data"
        const val ACTION_STOP = "app.plamotranslate.STOP_SCREEN_TRANSLATE"
        private const val DEBUG_TRANSLATE = "app.plamotranslate.DEBUG_TRANSLATE"
        private const val DEBUG_CONFIG = "app.plamotranslate.DEBUG_CONFIG"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()

        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenTranslateService::class.java)
                    .putExtra(EXTRA_RESULT_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenTranslateService::class.java).setAction(ACTION_STOP))
        }
    }
}

/** 設定の始め方を、状態遷移の始め方にする。 */
private fun ScreenTrigger.toTrigger() = when (this) {
    ScreenTrigger.TAP -> ScreenSession.Trigger.TAP
    ScreenTrigger.AUTO -> ScreenSession.Trigger.AUTO
}

/** 診断用：自分のプロセスの重要度（100=前面、125=フォアグラウンドサービス）。 */
private fun importance(): Int =
    ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
