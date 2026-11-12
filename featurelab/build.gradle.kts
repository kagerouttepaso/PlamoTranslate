// 機能テスト用のアプリ（2026-11-12）。画面翻訳の設計を決める前に、端末で何ができるかを1つずつ確かめる。
// 本体（app）とは別のパッケージ名で、配布しない。docs/資料/端末の能力.md
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plamotranslate.featurelab"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.plamotranslate.featurelab"
        minSdk = 34  // ユーザー補助の窓単位の撮影（takeScreenshotOfWindow）がAPI 34から
        targetSdk = 36
        versionCode = 1
        versionName = "0.1"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
}
