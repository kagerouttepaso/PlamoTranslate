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
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.Toast
import android.graphics.Bitmap
import app.plamotranslate.EngineHolder
import app.plamotranslate.ModelState
import app.plamotranslate.models.ModelCatalog
import app.plamotranslate.models.TranslationModel
import app.plamotranslate.ocr.MultiScriptOcr
import app.plamotranslate.ocr.OcrLanguage
import app.plamotranslate.overlay.TranslationCache
import app.plamotranslate.overlay.selectTargets
import app.plamotranslate.pipeline.translation.MemoryCache
import app.plamotranslate.pipeline.translation.Segment
import app.plamotranslate.pipeline.translation.SegmentUpdate
import app.plamotranslate.pipeline.translation.TranslationStream
import app.plamotranslate.translation.Translations
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch

/**
 * 画面翻訳のフォアグラウンドサービス（docs/05_画面翻訳.md）。
 * 浮遊ボタンを押すと1枚撮影し、文字を読んで日本語に訳し、元の文字の位置に重ねて出す。
 * 長押しで原文の言語の選択と終了のメニューを出す。
 *
 * 1回の翻訳の流れ（[translateScreen]）：撮影 → 文字認識（[MultiScriptOcr]）→ 訳す対象と枠の色の選別（[selectTargets]）
 * → 翻訳の流れ（[TranslationStream]）→ 重ね表示（[TranslationOverlay]）。
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
    private val ocr by lazy { MultiScriptOcr(ModelCatalog.mangaDir(this)) }
    private var job: Job? = null
    /** 翻訳のメモリの控え。1回の翻訳ごとに開く翻訳の流れで共有する（訳したモデルが変わったら捨てる）。 */
    private val memoryCache = MemoryCache()
    private var debugReceiver: BroadcastReceiver? = null
    private var orientation = Configuration.ORIENTATION_UNDEFINED

    // 自動で訳すときの見張り（[watchScreen]）。状態はすべてメインスレッドで触る
    private var autoJob: Job? = null
    private var settling = true
    private var settleStart = 0L
    private var stableSince = 0L
    private var previous: Thumb? = null
    private val recentThumbs = ArrayDeque<Thumb>()

    /** 動く画面のモードで、訳を出してから明るさが動いたます目（動く背景。比べない）。 */
    private var animated: BooleanArray? = null

    /** 訳の枠の下をのぞいている間（[peekUnderBoxes]）。 */
    private var peeking = false
    private var peekJob: Job? = null

    /** 撮っている間（自分の表示を隠している間）。見張りはこの間の画面を比べない。 */
    private var capturing = false

    /** 最後に訳したときの画面を縮めたもの（自分の表示を隠して撮った画面）。 */
    private var reference: Thumb? = null

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * 画面を回したら、前の向きで置いた訳の枠を消す（位置が合わなくなる）。訳している途中なら取り消す。
     * 浮遊ボタンは今の画面の同じ割合の位置へ移す。次に浮遊ボタンを押すと、撮影の大きさを今の向きに合わせてから撮る（[ScreenCapturer]）。
     */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.orientation == orientation) return
        orientation = newConfig.orientation
        job?.cancel()
        overlay?.clear()
        menu?.dismiss()
        button?.fitToScreen()
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
            it.onStatusTap = ::cancelAll
            it.opaqueWithAccessibility = { preferences.opaqueOverlay }
        }
        // ユーザー補助が有効なら、アプリの切り替え・戻る・ホームの知らせで、訳している途中の処理を取り消す
        OverlayAccessibilityService.onWindowChanged = ::onWindowChanged
        touchWatcher = TouchWatcher(this, wm, ::onScreenTouch).also { it.show() }
        menu = ScreenMenu(this, wm)
        button = FloatingButton(this, wm, onTap = ::onButtonTap, onLongPress = ::showMenu).also {
            it.setLabel(buttonLabel(preferences.language, preferences.trigger))
            it.show()
        }
        EngineHolder.ensureLoaded(this)
        applyTrigger(preferences.trigger)
        registerDebugReceiver()
        _running.value = true
        return START_NOT_STICKY
    }

    /**
     * 浮遊ボタンのタップ。訳の表示を一括でオン・オフする（2026-11-12、作者の要望。訳の枠1つずつのタップはやめた）。
     * - タップで訳すとき：訳を出していれば消し、出していなければ訳す。
     * - 自動で訳すとき：自動の見張りを止める（訳も消す）か、再び始める。止めている間はボタンを薄くする。
     */
    private fun onButtonTap() {
        menu?.dismiss()
        if (preferences.trigger == ScreenTrigger.AUTO) {
            if (autoJob?.isActive == true) {
                stopAuto()
                Toast.makeText(this, "自動翻訳を止めました。もう一度押すと再開します", Toast.LENGTH_SHORT).show()
            } else {
                startAuto()
            }
            return
        }
        if (overlay?.isShowing == true) {
            job?.cancel()
            overlay?.clear()
        } else {
            translateScreen()
        }
    }

    /** 始め方を反映する。自動なら見張りを始める。 */
    private fun applyTrigger(trigger: ScreenTrigger) {
        if (trigger == ScreenTrigger.AUTO) startAuto() else stopAuto()
    }

    /**
     * ほかのアプリの画面に触れたとき（[TouchWatcher]。自分のボタン・メニュー・表示への操作は除いてある）。
     * - 撮って訳している途中なら、撮影・文字認識・翻訳をすべて取り消して訳を消す（2026-11-12、作者の要望
     *   「自動翻訳で訳している途中に画面を操作したら全処理をキャンセル」）。自動なら待ちへ戻り、画面が止まったら訳し直す。
     * - タップで訳すときに訳を出していれば消す（スクロールなどで画面が変わるので）。
     * - 自動で訳を出していれば、訳の枠の下の文字が変わったかをのぞく（[peekUnderBoxes]）。
     */
    private fun onScreenTouch() {
        if (job?.isActive == true) {
            Log.i(TAG, "画面翻訳: 訳している途中に画面を操作したので取り消した")
            cancelAll()
            return
        }
        if (preferences.trigger == ScreenTrigger.TAP) {
            overlay?.clear()
            return
        }
        peekUnderBoxes()
    }

    /**
     * ほかのアプリの画面が切り替わったとき（ユーザー補助の知らせ。アプリの切り替え・戻る・ホーム）。
     * 訳している途中か訳を出していれば、すべて取り消して訳を消す。ジェスチャーの帯は重ね表示より手前の窓なので
     * [TouchWatcher] には触れた知らせが来ないが、ユーザー補助ならこの知らせで確実に分かる。
     */
    private fun onWindowChanged() {
        Log.d(TAG, "画面翻訳（調査）: ユーザー補助の画面の切り替わりの知らせ（訳している途中 ${job?.isActive == true}、訳を表示中 ${overlay?.isShowing == true}）")
        if (!preferences.opaqueOverlay) return
        if (job?.isActive == true || overlay?.isShowing == true) {
            Log.i(TAG, "画面翻訳: ほかのアプリの画面が切り替わったので取り消した")
            cancelAll()
        }
    }

    /** 撮影・文字認識・翻訳・のぞきをすべて取り消し、訳を消す。自動なら待ちへ戻る。 */
    private fun cancelAll() {
        job?.cancel()
        peekJob?.cancel()
        overlay?.clear()
        if (autoJob?.isActive == true) {
            reference = null
            beginSettling(SystemClock.uptimeMillis())
        }
    }

    private fun startAuto() {
        autoJob?.cancel()
        autoJob = scope.launch { watchScreen() }
        button?.setActive(true)
    }

    private fun stopAuto() {
        autoJob?.cancel()
        autoJob = null
        job?.cancel()
        overlay?.clear()
        button?.setActive(preferences.trigger != ScreenTrigger.AUTO)
    }

    /**
     * 自動で訳すときの見張り（2026-11-12、作者の要望「漫画のページ送りや、Discordでスクロールが止まったら、タップ不要で訳す」。
     * docs/05_画面翻訳.md「自動で訳す」）。[AUTO_POLL_MS] ごとに、新しく届いた画面を格子に縮めて比べる（[ScreenChange]）。
     *
     * 1. 待ち（[settling]）：直前の画面からの変化が [Thresholds.stable] 未満の状態が [AUTO_SETTLE_MS] 続いたら（画面が止まったら）訳す。
     *    新しい画面が届かないのも止まっている印。
     * 2. 表示中：訳したときの画面（[reference]）からの変化が [Thresholds.change] 以上なら（スクロール・ページ送り）、
     *    訳を消し（訳している途中なら取り消し）、1.へ戻る。自分の訳の枠・上部の表示・浮遊ボタンの下は比べない。
     *
     * 動く画面のモードでは、直近 [MOTION_THUMBS] 回の格子をます目ごとの中央値で1枚にしてから比べる（[ScreenChange.median]）。
     * 背景が動き続けるゲームでは、1回ごとの格子を比べると画面が止まったことにならず、いつまでも訳さなかった
     * （2026-11-12、大きな図形が動き回る試験の画面で、しきい値を12%に緩めても20秒間訳さなかった）。
     * それでも大きな図形が画面の多くを覆って動くと中央値に残るので、さらに直近の格子で明るさが動いたます目（[ScreenChange.active]）を
     * 比べる対象から外し、止まっているます目（UI・台詞の枠）だけで見る。訳を出している間に動き出したます目も外していく。
     * 待ちが [MOTION_MAX_WAIT_MS] を超えたら、止まるのを待たずに訳す。
     *
     * 訳の枠の真下の変化（ゲームの台詞送り）はここでは見えないので、触れたときに枠の下をのぞいて見る（[peekUnderBoxes]）。
     */
    private suspend fun watchScreen() {
        var lastAt = 0L
        var lastDiagAt = 0L
        recentThumbs.clear()
        beginSettling(SystemClock.uptimeMillis())
        while (true) {
            delay(AUTO_POLL_MS)
            val capturer = capturer ?: return
            // 撮っている間・枠の下をのぞいている間は自分の表示を隠しているので比べない。メニューを選んでいる間は訳さない
            if (capturing || peeking || menu?.isShowing == true) continue
            val sample = capturer.latestThumb(lastAt)
            val now = SystemClock.uptimeMillis()
            if (sample != null) lastAt = sample.second
            val motion = preferences.recognition == ScreenRecognition.MOTION
            val thumb = sample?.first?.let { t ->
                recentThumbs.addLast(t)
                while (recentThumbs.size > MOTION_THUMBS) recentThumbs.removeFirst()
                if (motion) ScreenChange.median(recentThumbs.toList()) else t
            }
            val active = if (motion && recentThumbs.size >= 3) ScreenChange.active(recentThumbs.toList()) else null
            val limits = thresholds(preferences.recognition)
            if (!settling) {
                if (thumb == null) continue
                val ref = reference
                if (ref == null) {
                    // 撮れなかったなど。訳していなければ待ちへ戻る
                    if (job?.isActive != true) beginSettling(now)
                    continue
                }
                // 戻る・ホームのジェスチャーやアプリの切り替えで画面が丸ごと変わったら、モードによらずすぐ取り消す
                if (switched(ref, sample?.first, motion)) {
                    Log.i(TAG, "自動翻訳: 画面が切り替わったので取り消した")
                    cancelAll()
                    continue
                }
                val masks = overlay?.occupiedRects().orEmpty() + listOfNotNull(button?.bounds)
                val mask = ScreenChange.union(ScreenChange.mask(thumb, masks), animated)
                val changed = ScreenChange.changedFraction(ref, thumb, mask)
                // 調査用（2026-11-12、訳している途中に戻っても取り消されない）：訳している間の見張りの判断を残す
                if (job?.isActive == true && now - lastDiagAt >= 500) {
                    lastDiagAt = now
                    val raw = sample?.first
                    val rawChanged = raw?.let { ScreenChange.changedFraction(ref, it, ScreenChange.mask(it, masks)) }
                    val hidden = mask.count { it } * 100 / mask.size
                    Log.d(TAG, "自動翻訳（調査）: 訳している間の変化 ${"%.2f".format(changed)}（しきい値 ${limits.change}）、" +
                        "1枚の変化 ${rawChanged?.let { "%.2f".format(it) }}（切り替わり ${if (motion) MOTION_SWITCH_CHANGE else SWITCH_CHANGE}）、" +
                        "比べないます目 $hidden%、枠 ${overlay?.boxBounds?.size}個、モード ${preferences.recognition}")
                }
                if (changed >= limits.change) {
                    Log.i(TAG, "自動翻訳: 画面が変わった（${"%.2f".format(changed)}）ので訳を消す")
                    job?.cancel()
                    overlay?.clear()
                    beginSettling(now)
                    previous = thumb
                } else if (active != null) {
                    animated = ScreenChange.union(active, animated)
                }
                continue
            }
            if (thumb != null) {
                val prev = previous
                previous = thumb
                val mask = ScreenChange.union(ScreenChange.mask(thumb, listOfNotNull(button?.bounds)), active)
                if (prev == null || ScreenChange.changedFraction(prev, thumb, mask) >= limits.stable) stableSince = now
            }
            val waitedTooLong = motion && now - settleStart >= MOTION_MAX_WAIT_MS
            if (now - stableSince >= AUTO_SETTLE_MS || waitedTooLong) {
                settling = false
                previous = null
                animated = active
                translateScreen(fromUser = false)
            }
        }
    }

    /**
     * 訳したときの画面 [ref] から、今届いた画面 [raw]（中央値にしていない1枚）が丸ごと切り替わったか（2026-11-12、作者の指摘
     * 「戻る・ホームのジェスチャーで、訳している途中の処理が取り消されない」）。
     * ジェスチャーの帯はシステムの窓で、重ね表示より手前にあるので、触れた知らせ（[TouchWatcher]）が届かない。
     * 動く画面のモードの見張りは動いたます目を外していくので、切り替わりの動きの最初のコマを動く背景として覚え、見逃していた。
     * そこで、中央値や動いたます目の除外を通さない1枚で、ます目の半分以上（動く画面は6割以上）が変わったかを見る。
     * 自分の訳の枠・上部の表示・浮遊ボタンの下は比べない。
     */
    private fun switched(ref: Thumb, raw: Thumb?, motion: Boolean): Boolean {
        raw ?: return false
        val masks = overlay?.occupiedRects().orEmpty() + listOfNotNull(button?.bounds)
        val changed = ScreenChange.changedFraction(ref, raw, ScreenChange.mask(raw, masks))
        return changed >= if (motion) MOTION_SWITCH_CHANGE else SWITCH_CHANGE
    }

    /**
     * タップで訳すとき、訳している間だけ画面の切り替わりを見張り、切り替わったら取り消す（自動のときは [watchScreen] が見る）。
     */
    private suspend fun guardSwitch(ref: Thumb) {
        var lastAt = SystemClock.uptimeMillis()
        val motion = preferences.recognition == ScreenRecognition.MOTION
        while (true) {
            delay(AUTO_POLL_MS)
            val sample = capturer?.latestThumb(lastAt) ?: continue
            lastAt = sample.second
            if (switched(ref, sample.first, motion)) {
                Log.i(TAG, "画面翻訳: 画面が切り替わったので取り消した")
                cancelAll()
                return
            }
        }
    }

    /** 見張りを待ち（画面が止まるのを待つ）に戻す。 */
    private fun beginSettling(now: Long) {
        settling = true
        settleStart = now
        stableSince = now
        previous = null
        animated = null
    }

    /**
     * 自動で訳しているとき、画面のどこかに触れたら（[onScreenTouch]）、訳の枠の下の文字が変わったかをのぞく
     * （2026-11-12。ゲームで台詞を送ると、変わるのは訳の枠の真下だけで、枠の下は比べないので見張りでは気づけなかった）。
     * 触れた操作に画面が応えるのを [PEEK_DELAY_MS] 待ってから、訳の枠を隠して撮り、訳したときの画面と枠の範囲だけで比べる。
     * 変わっていれば訳を消して待ちへ戻し（止まったら訳し直す）、変わっていなければ枠を戻す。
     */
    private fun peekUnderBoxes() {
        if (autoJob?.isActive != true || settling || capturing || peeking) return
        peekJob?.cancel()
        peekJob = scope.launch {
            delay(PEEK_DELAY_MS)
            if (autoJob?.isActive != true || settling || capturing || menu?.isShowing == true || job?.isActive == true) return@launch
            val overlay = overlay ?: return@launch
            val capturer = capturer ?: return@launch
            val ref = reference ?: return@launch
            val boxes = overlay.boxBounds.takeIf { it.isNotEmpty() } ?: return@launch
            val motion = preferences.recognition == ScreenRecognition.MOTION
            peeking = true
            var changed = 0f
            try {
                val hiddenAt = SystemClock.uptimeMillis()
                overlay.setBoxesVisible(false)
                delay(HIDE_SETTLE_MS)
                // 枠を隠した後の画面を撮る。動く画面のモードは3枚の中央値で背景の動きを消す
                val thumbs = mutableListOf<Thumb>()
                var after = hiddenAt + 16
                val deadline = SystemClock.uptimeMillis() + PEEK_TIMEOUT_MS
                while (thumbs.size < (if (motion) 3 else 1) && SystemClock.uptimeMillis() < deadline) {
                    val s = capturer.latestThumb(after)
                    if (s != null) {
                        thumbs += s.first
                        after = s.second + MOTION_PEEK_INTERVAL_MS
                    }
                    delay(16)
                }
                if (thumbs.isEmpty()) return@launch
                val now = if (thumbs.size >= 3) ScreenChange.median(thumbs) else thumbs.last()
                // 訳の枠の範囲だけを比べる。動く背景のます目は外さない。半透明の台詞の枠は奥を図形が通ると「動く背景」になり、
                // 外すと台詞が変わっても気づけなかった（2026-11-12）。誤って変わったとみても、控えがあるので訳し直しは速い
                val mask = ScreenChange.invert(ScreenChange.mask(now, boxes))
                changed = ScreenChange.changedFraction(ref, now, mask)
            } finally {
                peeking = false
            }
            Log.i(TAG, "自動翻訳: 触れたので訳の枠の下を見た（変化 ${"%.2f".format(changed)}）")
            if (changed >= PEEK_CHANGE) {
                overlay.clear()
                beginSettling(SystemClock.uptimeMillis())
            } else {
                overlay.setBoxesVisible(true)
            }
        }
    }

    /** 自動で訳すときの、変わった・止まったとみなすます目の割合。 */
    private class Thresholds(val change: Float, val stable: Float)

    private fun thresholds(mode: ScreenRecognition) = when (mode) {
        // 通常：画面の6%が変われば変わった（数行のスクロールで超える）、2%未満なら止まった（点滅するカーソルや小さなアイコンは許す）
        ScreenRecognition.NORMAL -> Thresholds(change = 0.06f, stable = 0.02f)
        // 動く画面：中央値で背景の動きを消し、動いたます目も外して比べる。それでも大きなキャラクターが新しい場所へ動くと
        // 9〜27%が変わった（2026-11-12の試験の画面）ので、画面の変化は場面転換・ページ送りくらい（35%以上）だけを見る。
        // 台詞送りのような枠の下の変化は、触れたときにのぞいて見る（peekUnderBoxes）
        ScreenRecognition.MOTION -> Thresholds(change = 0.35f, stable = 0.04f)
    }

    /**
     * 長押しのメニュー：原文の言語、認識モード、翻訳モデル、終了（2026-11-12に認識モードと翻訳モデルを足した。作者の要望）。
     * 翻訳モデルはアプリ全体で1つなので、ここで変えると会話・カメラ・テキストの画面のモデルも変わる。
     */
    private fun showMenu() {
        val (x, y) = button?.position ?: return
        // 訳の枠（特にユーザー補助の重ね表示）はメニューより手前に出て、メニューに触れなくなる（2026-11-12、作者の指摘）。
        // 開くときは訳を消し、訳している途中なら取り消す（取り消さないと、訳し終わった枠がメニューの上に出る）。
        // 自動で訳すときは、メニューを閉じた後に画面が止まっていれば訳し直す（訳し終えた分は控えから出る）
        cancelAll()
        val entries = buildList {
            add(MenuEntry.Header("原文の言語"))
            OcrLanguage.entries.forEach { lang ->
                add(MenuEntry.Item(lang.menuName, checked = lang == preferences.language) {
                    preferences.language = lang
                    button?.setLabel(buttonLabel(lang, preferences.trigger))
                })
            }
            add(MenuEntry.Header("翻訳の始め方"))
            ScreenTrigger.entries.forEach { t ->
                add(MenuEntry.Item(t.menuName, checked = t == preferences.trigger) {
                    preferences.trigger = t
                    button?.setLabel(buttonLabel(preferences.language, t))
                    applyTrigger(t)
                })
            }
            add(MenuEntry.Header("認識モード"))
            ScreenRecognition.entries.forEach { mode ->
                add(MenuEntry.Item(mode.menuName, checked = mode == preferences.recognition) { preferences.recognition = mode })
            }
            add(MenuEntry.Header("訳の枠"))
            add(MenuEntry.Item("元の文字に合わせる", checked = !preferences.fitBubbles) { preferences.fitBubbles = false })
            add(MenuEntry.Item("吹き出しいっぱいに広げる", checked = preferences.fitBubbles) { preferences.fitBubbles = true })
            add(MenuEntry.Header("翻訳モデル"))
            val state = EngineHolder.state.value
            val current = (state as? ModelState.Ready)?.model ?: EngineHolder.selected(this@ScreenTranslateService)
            TranslationModel.entries.forEach { m ->
                val present = m.isPresent(this@ScreenTranslateService)
                val label = if (present) m.label else "${m.label}（設定の画面からダウンロード）"
                add(MenuEntry.Item(label, checked = m == current, enabled = present && state !is ModelState.Loading) {
                    if (m != current) switchModel(m)
                })
            }
            add(MenuEntry.Header(""))
            add(MenuEntry.Item("画面翻訳を終了") { stopSelf() })
        }
        menu?.show(x, y, entries)
    }

    /** 翻訳モデルを切り替え、読み込みの進み具合を上部の小さな表示に出す。訳している途中なら取り消す（古いエンジンは閉じられる）。 */
    private fun switchModel(model: TranslationModel) {
        job?.cancel()
        overlay?.clear()
        // 自動で訳しているなら、新しいモデルで訳し直す（画面が止まっていれば読み込みの後すぐ訳す）
        reference = null
        beginSettling(SystemClock.uptimeMillis())
        EngineHolder.select(this, model)
        job = scope.launch {
            overlay?.showStatus("${model.label}を読み込んでいます…")
            val ready = EngineHolder.awaitReady(this@ScreenTranslateService)
            overlay?.showStatus(if (ready != null) "翻訳モデルを${ready.model.label}にしました" else "翻訳モデルを読めませんでした")
        }
    }

    /**
     * 画面を撮る。通常は1枚。動く画面のモードは続けて数枚撮り、画素ごとの中央値で重ねた1枚にする（[FrameStack]）。
     * 撮れなければ null。返す画像は呼ぶ側が捨てる。
     */
    private suspend fun captureScreen(capturer: ScreenCapturer, since: Long, mode: ScreenRecognition): Pair<Bitmap, Int>? {
        if (mode == ScreenRecognition.NORMAL) return capturer.capture(since)?.let { it to 1 }
        val frames = capturer.captureFrames(since, MOTION_FRAMES, MOTION_INTERVAL_MS)
        if (frames.isEmpty()) return null
        if (frames.size == 1) return frames[0] to 1
        val stacked = try {
            FrameStack.median(frames)
        } finally {
            frames.forEach { it.recycle() }
        }
        return stacked to frames.size
    }

    /** 画面を撮って訳す。[fromUser] が false（自動の見張りから）なら、メニューは閉じない。 */
    private fun translateScreen(fromUser: Boolean = true) {
        if (fromUser) menu?.dismiss()
        job?.cancel()
        job = scope.launch {
            val overlay = overlay ?: return@launch
            val capturer = capturer ?: return@launch
            val language = preferences.language
            val mode = preferences.recognition

            // 自分のボタンと前の訳が写らないよう、消してから撮る
            val hiddenAt = SystemClock.uptimeMillis()
            Log.d(TAG, "画面翻訳（調査）: 訳し始める（${if (fromUser) "タップ" else "自動"}、モード $mode、残っていた訳の枠 ${overlay.boxBounds.size}個）")
            capturing = true
            reference = null
            val captured = try {
                button?.setVisible(false)
                overlay.clear()
                delay(HIDE_SETTLE_MS)
                // 消した後に描かれた画面（1フレーム＝約16ms後以降に届いたもの）を使う
                captureScreen(capturer, since = hiddenAt + 16, mode)
            } finally {
                button?.setVisible(true)
                capturing = false
            }
            val captureMillis = SystemClock.uptimeMillis() - hiddenAt
            if (captured == null) {
                overlay.showStatus("画面を撮影できませんでした")
                return@launch
            }
            val (bitmap, frameCount) = captured
            val capturedSize = "${bitmap.width}x${bitmap.height}"
            // 自動で訳すとき、ここからの画面の変化を見る（訳の枠を置く前の画面）
            val ref = ScreenChange.thumb(bitmap).also { reference = it }
            // タップで訳すときは、訳している間だけ画面の切り替わり（戻る・ホームのジェスチャーなど）を見張る
            if (preferences.trigger == ScreenTrigger.TAP) {
                val guard = scope.launch { guardSwitch(ref) }
                coroutineContext[Job]?.invokeOnCompletion { guard.cancel() }
            }

            overlay.showStatus("文字を読んでいます…")
            val t0 = SystemClock.uptimeMillis()
            val blocks = try {
                // 動く画面のモード（ゲーム）は、白縁取り・白抜きの文字にも強い読み方にする（OutlinedText）
                if (mode == ScreenRecognition.MOTION) ocr.recognizeOutlined(bitmap, language) else ocr.recognize(bitmap, language)
            } catch (e: Exception) {
                bitmap.recycle()
                if (e is CancellationException) throw e
                overlay.showStatus("文字を読めませんでした: ${e.message}")
                return@launch
            }
            val ocrMillis = SystemClock.uptimeMillis() - t0
            val statusBar = statusBarHeight()
            val targets = selectTargets(blocks, bitmap, statusBar, SCREEN_TARGET, fitBubbles = preferences.fitBubbles)
            bitmap.recycle()
            if (targets.isEmpty()) {
                overlay.showStatus("訳す文字が見つかりませんでした（文字認識 ${ocrMillis}ms）")
                return@launch
            }
            overlay.show(targets.map { it.slot }, "${targets.size}件を翻訳中…")

            val ready = EngineHolder.awaitReady(this@ScreenTranslateService)
            if (ready == null) {
                overlay.showStatus("翻訳モデルを読めません。アプリを開いて確認してください")
                return@launch
            }
            val t1 = SystemClock.uptimeMillis()
            // 1回の翻訳ごとに翻訳の流れを開き、終わったら閉じる（docs/13_画面翻訳の構成.md「翻訳の契約」）。
            // メモリの控えはサービスが1つ持って流れどうしで共有し、訳したモデルが変わったら捨てる。
            // 控えは端末のデータベースにも残し、次に始めたときも使う。データベースでは確かなモデル（2B）の訳を優先する（TranslationCache.Scope）
            val source = language.sourceLanguage
            val store = TranslationCache.get(this@ScreenTranslateService)
            val stream = Translations.open(this, this@ScreenTranslateService, TranslationStream.Options(source, SCREEN_TARGET), memoryCache) { r ->
                Translations.PersistentMemory(store.scope(r.model.file.sha256.take(16), r.model.cacheRank, source, SCREEN_TARGET), store.generation)
            }
            var failure: String? = null
            try {
                stream.submit(targets.mapIndexed { i, t -> Segment(i.toLong(), t.sourceText) })
                val done = HashSet<Long>()
                stream.updates.transformWhile { u ->
                    when (u) {
                        is SegmentUpdate.Partial -> overlay.setTranslation(u.id.toInt(), u.text)
                        is SegmentUpdate.Final -> {
                            overlay.setTranslation(u.id.toInt(), u.text)
                            done += u.id
                        }
                        is SegmentUpdate.Failed -> {
                            failure = u.message
                            done += u.id
                        }
                    }
                    emit(u)
                    done.size < targets.size
                }.collect { }
                val st = stream.stats
                Log.i(TAG, "まとめ訳し: ${targets.size}件、控え メモリ${st.memoryHits}・データベース${st.storedHits}、訳した${st.pieces}片（訳し直し${st.retried}、打ち切り${st.stopped}）")
            } finally {
                stream.close()
            }
            failure?.let {
                overlay.showStatus("翻訳に失敗しました: $it")
                return@launch
            }
            val translateMillis = SystemClock.uptimeMillis() - t1
            // 動く画面のモードは撮る時間（数枚を撮って重ねる）も待ち時間なので含める
            val totalMillis = ocrMillis + translateMillis + if (frameCount > 1) captureMillis else 0
            val frames = if (frameCount > 1) "・${frameCount}枚重ね" else ""
            overlay.showStatus("${targets.size}件・${"%.1f".format(totalMillis / 1000.0)}秒・${ready.model.label}$frames")
            Log.i(TAG, "画面翻訳: ${blocks.size}ブロック中${targets.size}件、撮影 ${captureMillis}ms（${frameCount}枚）、文字認識 ${ocrMillis}ms、翻訳 ${translateMillis}ms、モデル ${ready.model.label}、重要度 ${importance()}、画面 ${capturedSize}、状態バー ${statusBar}px")
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
                    DEBUG_TRANSLATE -> translateScreen()
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
        if (OverlayAccessibilityService.onWindowChanged != null) OverlayAccessibilityService.onWindowChanged = null
        scope.cancel()
        button?.remove()
        touchWatcher?.remove()
        overlay?.clear()
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
        private const val HIDE_SETTLE_MS = 50L

        /**
         * 動く画面のモードで重ねる枚数と間隔。約0.5秒の間に5枚撮る。中央値は半分未満のコマ（5枚中2枚まで）にしか
         * 写らない動きを消すので、0.5秒のうち0.2秒ほどしか同じ画素に留まらない動きが消える。間隔を広げると遅い動きも消えるが、
         * その分だけ待つ。ゲームで試して合わなければ変える（docs/05_画面翻訳.md「動く画面のモード」）。
         */
        private const val MOTION_FRAMES = 5
        private const val MOTION_INTERVAL_MS = 110L

        /** 自動で訳すとき、画面を見る間隔。縮めた格子を比べるだけなので軽い。 */
        private const val AUTO_POLL_MS = 200L

        /** 自動で訳すとき、画面がこれだけ止まっていたら訳す（スクロールの慣性が止まり、ページ送りの動きが終わるのを待つ）。 */
        private const val AUTO_SETTLE_MS = 600L

        /** 動く画面のモードの見張りで、中央値を取る格子の数（200msごとなので約1秒分）。 */
        private const val MOTION_THUMBS = 5

        /** 動く画面のモードで、画面が止まるのを待つ上限。背景が大きく動き続けても、これだけ待てば訳す。 */
        private const val MOTION_MAX_WAIT_MS = 3000L

        /** 触れてから訳の枠の下をのぞくまでの待ち（台詞送りなど、触れた操作に画面が応えて描き終わるのを待つ）。 */
        private const val PEEK_DELAY_MS = 350L

        /** 訳の枠を隠してから、のぞく画面が届くのを待つ上限。 */
        private const val PEEK_TIMEOUT_MS = 600L

        /** 動く画面のモードで、のぞくときに撮る3枚の間隔。 */
        private const val MOTION_PEEK_INTERVAL_MS = 80L

        /** のぞいた訳の枠の範囲で、これだけのます目が変われば、枠の下の文字が変わったとみなす。 */
        private const val PEEK_CHANGE = 0.15f

        /** 画面が丸ごと切り替わったとみなす、変わったます目の割合（[switched]）。動く画面のモードは背景の動きの分だけ高くする。 */
        private const val SWITCH_CHANGE = 0.5f
        private const val MOTION_SWITCH_CHANGE = 0.6f
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

/** 診断用：自分のプロセスの重要度（100=前面、125=フォアグラウンドサービス）。 */
private fun importance(): Int =
    ActivityManager.RunningAppProcessInfo().also { ActivityManager.getMyMemoryState(it) }.importance
