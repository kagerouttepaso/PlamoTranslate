// ログの出口（docs/設計/アーキテクチャ.md「ログ」）。どのモジュールのログも Logs を通し、書き先（logcat・アプリのファイル）は
// app が差す。端末にもライブラリにも依存しない Kotlin/JVM のモジュールにして、core-* のどれからも使えるようにする。
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
    testImplementation(libs.junit)
}
