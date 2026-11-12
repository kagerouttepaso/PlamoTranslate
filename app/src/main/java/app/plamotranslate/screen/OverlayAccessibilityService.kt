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
 * もう1つ、ほかのアプリの画面の切り替わり（TYPE_WINDOW_STATE_CHANGED：アプリの切り替え、戻る・ホーム）を受け、
 * 訳している途中なら取り消す合図にする（[onWindowChanged]）。画面の文字は読み取らない（canRetrieveWindowContent="false"）。
 */
class OverlayAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
        _connected.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        // 自分の重ね表示やメニューの出し入れは、画面の切り替わりではない
        if (event.packageName == packageName) return
        onWindowChanged?.invoke()
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

        /** ユーザー補助の設定で有効になっているか（サービスがまだつながっていなくても）。 */
        fun isEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            val name = "${context.packageName}/${OverlayAccessibilityService::class.java.name}"
            return enabled.split(':').any { it.equals(name, ignoreCase = true) }
        }
    }
}
