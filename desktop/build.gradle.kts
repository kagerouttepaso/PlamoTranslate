import java.io.File

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.desktop)
}

kotlin { jvmToolchain(21) }

dependencies {
    implementation(project(":core-pipeline"))
    implementation(project(":core-llm-jvm"))
    implementation(project(":core-log"))
    implementation(project(":core-asr-sherpa"))
    runtimeOnly(project(":core-asr-sherpa", configuration = "sherpaJvmApi"))
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.desktop.material3)
    implementation(libs.kotlinx.coroutines.swing)
    implementation("org.apache.pdfbox:pdfbox:3.0.7")
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val desktopJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
val desktopNativeDir = rootProject.file(providers.gradleProperty("desktopNativeDir").getOrElse("desktop-native/build/bin"))

tasks.withType<Test>().configureEach {
    systemProperty("java.library.path", desktopNativeDir.absolutePath)
    environment("PATH", desktopNativeDir.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty())
}

val desktopSherpaDir = rootProject.file(providers.gradleProperty("desktopSherpaDir")
    .getOrElse("third_party/sherpa-onnx/sherpa-onnx-v${libs.versions.sherpa.onnx.get()}-win-x64-jni/lib"))

// 配布物に入れる DLL（翻訳・PDF・音声のネイティブと sherpa-onnx）を、Compose の appResources の windows/ にまとめる。
val stageNativeResources by tasks.registering(Sync::class) {
    from(desktopNativeDir) { include("*.dll") }
    from(desktopSherpaDir) { include("*.dll") }
    into(layout.buildDirectory.dir("distribution-resources/windows"))
}

compose.desktop {
    application {
        mainClass = "app.plamotranslate.desktop.MainKt"
        javaHome = desktopJava.get().metadata.installationPath.asFile.absolutePath
        // JNI・PDFBox・sherpa-onnx はリフレクションと JNI から呼ばれるので、縮小（ProGuard）はかけない。
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            packageName = "Mockingword"
            packageVersion = "0.1.0"
            appResourcesRootDir.set(layout.buildDirectory.dir("distribution-resources"))
            includeAllModules = true
            // jpackage が $APPDIR をアプリの app フォルダーに置き換える。DLL は app/resources に入る。
            jvmArgs += listOf(
                "-Djava.library.path=\$APPDIR/resources",
                "-Dmockingword.native.dir=\$APPDIR/resources",
                "-Dmockingword.sherpa.dir=\$APPDIR/resources",
                "-Dstdout.encoding=UTF-8",
                "-Dstderr.encoding=UTF-8",
            )
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageNativeResources) }

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val nativeDir = desktopNativeDir
    val sherpaVersion = libs.versions.sherpa.onnx.get()
    val sherpaDir = rootProject.file(providers.gradleProperty("desktopSherpaDir")
        .getOrElse("third_party/sherpa-onnx/sherpa-onnx-v$sherpaVersion-win-x64-jni/lib"))
    val dllPath = listOf(nativeDir, sherpaDir).joinToString(File.pathSeparator) { it.absolutePath }
    systemProperty("java.library.path", dllPath)
    systemProperty("mockingword.native.dir", nativeDir.absolutePath)
    systemProperty("mockingword.sherpa.dir", sherpaDir.absolutePath)
    systemProperty("mockingword.cpuOnly", providers.gradleProperty("desktopCpuOnly").getOrElse("false"))
    // Gradle が UTF-8 で読み取る子プロセスの出力をそろえる。
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    environment("PATH", dllPath + File.pathSeparator + System.getenv("PATH").orEmpty())
}
