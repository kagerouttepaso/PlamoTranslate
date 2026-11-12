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
 * docs/設計/画面翻訳.md「訳文の重ね表示」）。
 *
 * ほかのアプリの重ね表示（TYPE_APPLICATION_OVERLAY）は、触れた操作を下のアプリへ通すと不透明度0.8までに制限され、元の文字が透けた。
 * ユーザー補助の重ね表示（TYPE_ACCESSIBILITY_OVERLAY）はこの制限を受けないので、不透明のまま操作を通せる。
 * 窓はこのサービスの WindowManager からしか置けないので、訳の枠（[TranslationOverlay]）はこのサービスを通して置く。
 *
 * もう1つ、ほかのアプリの画面の切り替わり（TYPE_WINDOW_STATE_CHANGED：アプリの切り替え・ホーム・最近使ったアプリ・別の画面）と
 * スクロール（TYPE_VIEW_SCROLLED）を受け、訳している途中の処理や古くなった訳を取り消す合図にする（[onWindowChanged]・[onScrolled]）。
 * 戻る操作そのものは受け取れない（ジェスチャーの戻るはキーとして届かない。docs/資料/端末の能力.md「問い1」）。
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
                // 窓の中身を読まない権限でも、移動量は届く（2026-11-12、設定アプリで dy=404 などを確かめた）。
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
         * （2026-11-12、docs/資料/端末の能力.md「設計に効く、思わぬ事実」）。
         */
        fun isDetached(context: Context): Boolean = isEnabled(context) && !connected.value

        /**
         * ユーザー補助の設定で有効になっているか（サービスがまだつながっていなくても）。
         * 設定の値は、システムが「パッケージ名/クラスの完全な名前」でも「パッケージ名/.短い名前」でも書くので、
         * 部品の名前（ComponentName）に戻して比べる（2026-11-12、オン・オフし直した後は短い形で書かれ、完全な名前とだけ比べて見落とした）。
         */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val me = android.content.ComponentName(context, OverlayAccessibilityService::class.java)
            return enabled.split(':').any { android.content.ComponentName.unflattenFromString(it) == me }
        }
    }
}
