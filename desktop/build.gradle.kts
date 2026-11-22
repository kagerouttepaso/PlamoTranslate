import java.io.File
import java.util.zip.ZipFile
import org.jetbrains.compose.desktop.application.tasks.AbstractJPackageTask

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

val desktopVersion = "0.1.0"
val libraryNotices = layout.buildDirectory.file("licenses/JVM.txt")
val collectLibraryNotices by tasks.registering {
    val jars = configurations.runtimeClasspath
    inputs.files(jars)
    outputs.file(libraryNotices)
    doLast {
        val output = libraryNotices.get().asFile
        output.parentFile.mkdirs()
        output.bufferedWriter().use { writer ->
            jars.get().files.filter { it.extension == "jar" }.sortedBy { it.name }.forEach { jar ->
                ZipFile(jar).use { zip ->
                    val notices = zip.entries().asSequence().filter { entry ->
                        !entry.isDirectory && entry.name.substringAfterLast('/').matches(
                            Regex("(license|notice|copying)([-.].*)?", RegexOption.IGNORE_CASE))
                    }
                    for (entry in notices) {
                        writer.appendLine("${jar.name} — ${entry.name}")
                        zip.getInputStream(entry).bufferedReader().use { it.copyTo(writer) }
                        writer.appendLine().appendLine()
                    }
                }
            }
        }
    }
}

// 配布物に入れる DLL（翻訳・PDF・音声のネイティブと sherpa-onnx）を、Compose の appResources の windows/ にまとめる。
val stageNativeResources by tasks.registering(Sync::class) {
    from(desktopNativeDir) { include("*.dll") }
    from(desktopSherpaDir) { include("*.dll") }
    into("licenses") {
        from(rootProject.file("third_party/llama.cpp/LICENSE")) { rename { "llama.cpp.txt" } }
        from(rootProject.file("third_party/llama.cpp/licenses")) { into("llama.cpp") }
        from("licenses")
        from(rootProject.file("LICENSE")) { rename { "Mockingword.txt" } }
        from(rootProject.file("app/src/main/assets/licenses")) {
            include("apache-2.0.txt", "funasr-model-license.txt", "onnxruntime-third-party-notices.txt")
        }
        from(rootProject.file("third_party/pdfium/chromium-8165-win-x64")) { include("LICENSE", "licenses/**"); into("PDFium") }
        from(rootProject.file("third_party/harfbuzz/harfbuzz-12.3.2/COPYING")) { rename { "HarfBuzz.txt" } }
        from(collectLibraryNotices)
    }
    into(layout.buildDirectory.dir("distribution-resources/windows"))
}

compose.desktop {
    application {
        mainClass = "app.plamotranslate.desktop.MainKt"
        javaHome = desktopJava.get().metadata.installationPath.asFile.absolutePath
        // JNI・PDFBox・sherpa-onnx はリフレクションと JNI から呼ばれるので、縮小（ProGuard）はかけない。
        buildTypes.release.proguard { isEnabled.set(false) }
        jvmArgs += listOf("-Dmockingword.version=$desktopVersion", "-Dstdout.encoding=UTF-8", "-Dstderr.encoding=UTF-8")
        nativeDistributions {
            packageName = "Mockingword"
            packageVersion = desktopVersion
            appResourcesRootDir.set(layout.buildDirectory.dir("distribution-resources"))
            includeAllModules = true
        }
    }
}

tasks.matching { it.name == "prepareAppResources" }.configureEach { dependsOn(stageNativeResources) }

// $APPDIR を展開できるのは jpackage のランチャーだけ。
afterEvaluate {
    tasks.withType<AbstractJPackageTask>().configureEach {
        launcherJvmArgs.add("-Djava.library.path=\$APPDIR/resources")
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val resources = tasks.named("prepareAppResources").get().outputs.files.singleFile
    systemProperty("java.library.path", resources.absolutePath)
    systemProperty("mockingword.cpuOnly", providers.gradleProperty("desktopCpuOnly").getOrElse("false"))
}
