package app.plamotranslate

import android.app.Application
import app.plamotranslate.diagnostics.Diagnostics

/**
 * アプリのプロセス。どの入口（トップ画面・タイル・画面翻訳のサービス）から始まっても、最初に不具合の記録を用意する
 * （[Diagnostics]）。ほかの初期化はここに置かず、使う画面やサービスが持つ。
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        // バックエンド比較の別プロセスでは、不具合の記録やテーマの初期化を行わない
        if (android.app.Application.getProcessName().endsWith(":gpu_probe")) return
        Diagnostics.install(this)
        ThemePreferences.load(this)
    }
}
