// 中核（2026-11-12）。端末にもライブラリにも依存しない、判断と計画の部品を置く（docs/設計/アーキテクチャ.md「層とモジュール」）。
// Android に依存できない Kotlin/JVM のモジュールにして、依存の向き（外側 → 中核）をビルドで強制する。単体テストはここで書く。
plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
