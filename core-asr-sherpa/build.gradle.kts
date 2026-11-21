import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

val sherpaAar = rootProject.file("third_party/sherpa-onnx/sherpa-onnx-static-link-onnxruntime-${libs.versions.sherpa.onnx.get()}.aar")
val sherpaClassesDir = layout.buildDirectory.dir("sherpa-classes")
val sherpaClassesJar = sherpaClassesDir.map { it.file("classes.jar") }
val extractSherpaClasses by tasks.registering(Copy::class) {
    from(zipTree(sherpaAar)) { include("classes.jar") }
    into(sherpaClassesDir)
}

// JNI の Kotlin API は Android 用のコンストラクター型を含む。ファイルから読むので実行時には不要。
val androidJar = providers.provider {
    val sdk = Properties().apply {
        val local = rootProject.file("local.properties")
        if (local.isFile) local.inputStream().use(::load)
    }.getProperty("sdk.dir") ?: System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    requireNotNull(sdk) { "Android SDK の場所を local.properties または ANDROID_HOME に設定してください。" }
    File(sdk, "platforms/android-36/android.jar")
}

// Android アプリは AAR を同梱し、PC アプリとリプレイはこのクラスと Windows の JNI をそろえる。
val sherpaJvmApi by configurations.creating {
    isCanBeConsumed = true
    isCanBeResolved = false
}
artifacts.add(sherpaJvmApi.name, sherpaClassesJar) { builtBy(extractSherpaClasses) }

dependencies {
    api(project(":core-pipeline"))
    compileOnly(files(sherpaClassesJar).builtBy(extractSherpaClasses))
    compileOnly(files(androidJar))
    testImplementation(libs.junit)
}
