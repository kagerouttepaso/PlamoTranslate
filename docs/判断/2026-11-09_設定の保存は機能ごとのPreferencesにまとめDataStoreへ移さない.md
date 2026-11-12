# 設定の保存は機能ごとのPreferencesにまとめDataStoreへ移さない

- 状態：有効
- 問題：SharedPreferencesの保存のキー・既定値・旧形式の読み替えが各所に散っていた。
- 決めたこと：SharedPreferencesを開くのは `TranslationPreferences`・`SpeechPreferences`・`ScreenPreferences`・`CameraPreferences`・`ConversationPreferences` だけにする。名前は、設定の画面のファイル（`SpeechSettings.kt` など）と紛れないよう `*Preferences` にする。
- 理由：設定は数個の値だけで、DataStoreへ移すと読み書きの形と各画面の書き換えが要る。
- 捨てた案：DataStoreへ移す。名前を `*Settings` にする。
- 再発を防ぐもの：設定を開く場所を機能ごとの `*Preferences` に限る（`docs/設計/アーキテクチャ.md`「データの置き場」）。
