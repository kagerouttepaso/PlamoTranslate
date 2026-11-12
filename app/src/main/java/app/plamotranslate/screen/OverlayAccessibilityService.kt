package app.plamotranslate.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 画面翻訳の訳の枠を、透けずに重ねるためのユーザー補助のサービス（2026-11-12、作者の採用。設定の画面で切り替える。
 * docs/05_画面翻訳.md「透けない訳の枠」）。
 *
 * ほかのアプリの重ね表示（TYPE_APPLICATION_OVERLAY）は、触れた操作を下のアプリへ通すと不透明度0.8までに制限され、元の文字が透けた。
 * ユーザー補助の重ね表示（TYPE_ACCESSIBILITY_OVERLAY）はこの制限を受けないので、不透明のまま操作を通せる。
 * 窓はこのサービスの WindowManager からしか置けないので、訳の枠（[TranslationOverlay]）はこのサービスを通して置く。
 *
 * もう1つ、ほかのアプリの画面の切り替わり（TYPE_WINDOW_STATE_CHANGED：アプリの切り替え・ホーム・最近使ったアプリ・別の画面）と
 * スクロール（TYPE_VIEW_SCROLLED）を受け、訳している途中の処理や古くなった訳を取り消す合図にする（[onWindowChanged]・[onScrolled]）。
 * 戻る操作そのものは受け取れない（ジェスチャーの戻るはキーとして届かない。docs/12_機能テスト.md「問い1」）。
 * 画面の文字は読み取らない（canRetrieveWindowContent="false"）。出来事の種類とスクロールの量だけを使う。
 */
class OverlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        // 自分の重ね表示やメニューの出し入れは、画面の切り替わりではない
        if (event.packageName == packageName) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> onWindowChanged?.invoke()
            AccessibilityEvent.TYPE_VIEW_SCROLLED -> {
                // 調査用（2026-11-12、スクロールの合図が効かない）：窓の中身を読まない権限で移動量が取れるかを見る
                android.util.Log.d("PlamoTranslate", "スクロールの知らせ（調査）: ${event.packageName} dx=${event.scrollDeltaX} dy=${event.scrollDeltaY} 位置=${event.scrollX},${event.scrollY} 項目=${event.fromIndex}-${event.toIndex}/${event.itemCount}")
                // 一覧の組み直しなどで、動いていないスクロールの知らせも来る（Discordで dx=dy=0）。通知の引き出しも除く
                val moved = event.scrollDeltaX != 0 || event.scrollDeltaY != 0
                if (moved && event.packageName != SYSTEM_UI) onScrolled?.invoke()
            }
        }
    }

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
            _connected.value = false
        }
        super.onDestroy()
    }

    companion object {
        @Volatile
        var instance: OverlayAccessibilityService? = null
            private set

        private val _connected = MutableStateFlow(false)

        /** ユーザー補助の設定でこのサービスが有効になり、つながっているか。 */
        val connected: StateFlow<Boolean> = _connected.asStateFlow()

        /** ほかのアプリの画面が切り替わったとき（メインスレッド）。画面翻訳のサービスが動いている間だけ入れる。 */
        @Volatile
        var onWindowChanged: (() -> Unit)? = null

        /** ほかのアプリの中身がスクロールしたとき（メインスレッド）。画面翻訳のサービスが動いている間だけ入れる。 */
        @Volatile
        var onScrolled: (() -> Unit)? = null

        private const val SYSTEM_UI = "com.android.systemui"

        /**
         * ユーザー補助の設定で有効なのに、サービスがつながっていない（端末に止められて切り離された）か。
         * REDMAGICのゲーム支援は、ゲームを開いたときにユーザー補助を止め、利用者がオフ・オンし直すまで切り離したままにする
         * （2026-11-12、docs/12_機能テスト.md「設計に効く、思わぬ事実」）。
         */
        fun isDetached(context: Context): Boolean = isEnabled(context) && !connected.value

        /** ユーザー補助の設定で有効になっているか（サービスがまだつながっていなくても）。 */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val name = "${context.packageName}/${OverlayAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(name, ignoreCase = true) }
        }
    }
}
