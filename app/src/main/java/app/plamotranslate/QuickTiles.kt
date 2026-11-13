package app.plamotranslate

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import app.plamotranslate.camera.CameraTranslateActivity
import app.plamotranslate.conversation.ConversationActivity

/*
 * クイック設定（通知欄を下ろしたところ）のタイル。画面翻訳は始め・止める切り替え（screen/ScreenTranslateTileService）、
 * カメラ翻訳と会話翻訳は画面を開くだけ（2026-11-13、作者の要望）。開き方はアイコンの長押しメニュー（res/xml/shortcuts.xml）と同じ。
 */

/** 通知欄を閉じてから [intent] の画面を開く。 */
internal fun TileService.openCollapsing(intent: Intent) {
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
        startActivityAndCollapse(PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE))
    } else {
        // Android 13（最低版）では PendingIntent 版が無いので、Intent 版を使う
        @Suppress("DEPRECATION")
        @SuppressLint("StartActivityAndCollapseDeprecated")
        startActivityAndCollapse(intent)
    }
}

/** 押すと画面を開くだけのタイル。切り替えの状態を持たないので、いつも押せる見た目にする。 */
abstract class OpenScreenTileService : TileService() {
    /** 開く画面。 */
    protected abstract fun screen(): Intent

    override fun onStartListening() {
        val tile = qsTile ?: return
        tile.state = Tile.STATE_INACTIVE
        tile.updateTile()
    }

    override fun onClick() = openCollapsing(screen())
}

/** クイック設定の「カメラ翻訳」タイル。 */
class CameraTranslateTileService : OpenScreenTileService() {
    override fun screen() = Intent(this, CameraTranslateActivity::class.java)
}

/** クイック設定の「会話翻訳」タイル。 */
class ConversationTileService : OpenScreenTileService() {
    override fun screen() = Intent(this, ConversationActivity::class.java)
}
