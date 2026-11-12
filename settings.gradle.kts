pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "PlamoTranslate"
include(":app")
// 中核：端末に依存しない判断と計画（docs/設計/アーキテクチャ.md「層とモジュール」）
include(":core-pipeline")
include(":core-llm")
include(":core-ocr")
include(":core-asr")
include(":core-tts")
// 機能テスト用のアプリ（配布しない。docs/資料/端末の能力.md）
include(":featurelab")
