# IDEを使わずJDK21とgradlewでビルドする

- 状態：有効
- 問題：PCにJDK 17を入れておらず、Android Studio同梱のJBR 25はGradle 8.14.3が対応していない。
- 決めたこと：Android Studioを使わず、`gradlew.bat` でビルドして入れる。JDK 21で動かし、出力は全モジュールJava 17相当にする（`jvmToolchain(17)` は使わない）。`core-pipeline`（Kotlin/JVM）は `jvmToolchain(21)` を指定するので、JDK 21が必須。
- 理由：JDK 21ならGradleが動き、出力はJava 17相当に指定できる。
- 再発を防ぐもの：なし（選択）。手順は `docs/手順/開発環境.md`「PCに入れるもの」。
