plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plamotranslate.asr"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

// 会話の録音をリプレイする試験が使う、sherpa-onnx の AAR の中のクラス
val sherpaAar = rootProject.file("third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-${libs.versions.sherpa.onnx.get()}.aar")
val sherpaClassesDir = layout.buildDirectory.dir("sherpa-classes")
val sherpaClassesJar = sherpaClassesDir.map { it.file("classes.jar") }
val extractSherpaClasses by tasks.registering(Copy::class) {
    from(zipTree(sherpaAar)) { include("classes.jar") }
    into(sherpaClassesDir)
}

dependencies {
    // sherpa-onnx（音声認識）。ONNX Runtimeを内部に静的に組み込んだ版で、漫画用OCRのONNX Runtimeと
    // ライブラリ名がぶつからない。AARは tools/get-sherpa-onnx.ps1 で取得する（Gitに入れない）。2026-11-06作者承認（大きな依存）。
    // ライブラリモジュールはローカルAARを同梱できないので、ここではコンパイルだけに使い、appで同梱する
    compileOnly(files(sherpaAar))
    // 中核のインターフェース（VoiceActivity・SpeechRecognizer）を実装し、中核の区切り（SpeechEngine）を返す
    api(project(":core-pipeline"))
    implementation(project(":core-log"))
    // 言語の識別のスコア（Whisper tiny を直接動かす）。漫画用OCRと同じ版（sherpa-onnx は ONNX Runtime を静的に組み込んでいて、名前がぶつからない）
    implementation(libs.onnxruntime.android)
    testImplementation(libs.junit)
    testImplementation(libs.onnxruntime.jvm)
    // 会話の録音を PC でリプレイする試験（ConversationReplayTest）が、端末と同じ VAD と書き起こしを使うため。
    // AAR の中の classes.jar を取り出して使う。ネイティブのライブラリ（Windows 版の JNI）は PLAMO_SHERPA_LIB で指す
    testImplementation(files(sherpaClassesJar).builtBy(extractSherpaClasses))
}

// リプレイの試験は、PLAMO_SHERPA_LIB（sherpa-onnx の Windows 版の JNI の lib）があるときだけ動く（docs/手順/開発環境.md「会話の録音をリプレイする」）
tasks.withType<Test>().configureEach {
    System.getenv("PLAMO_SHERPA_LIB")?.let { lib ->
        systemProperty("java.library.path", lib)
        environment("PATH", lib + File.pathSeparator + System.getenv("PATH"))
    }
}

// JDK 21で動かし、Java 17相当のバイトコードを出す（JDK 17は入れていない）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
