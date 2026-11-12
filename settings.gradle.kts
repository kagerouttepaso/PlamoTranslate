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
include(":core-llm")
include(":core-ocr")
include(":core-asr")
include(":core-tts")
// 機能テスト用のアプリ（配布しない。docs/12_機能テスト.md）
include(":featurelab")
