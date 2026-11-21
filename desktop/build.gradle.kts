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
    implementation(compose.desktop.currentOs)
    implementation(libs.compose.desktop.material3)
    implementation(libs.kotlinx.coroutines.swing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

val desktopJava = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }

compose.desktop {
    application {
        mainClass = "app.plamotranslate.desktop.MainKt"
        javaHome = desktopJava.get().metadata.installationPath.asFile.absolutePath
    }
}

tasks.withType<JavaExec>().matching { it.name == "run" }.configureEach {
    val nativeDir = rootProject.file(providers.gradleProperty("desktopNativeDir").getOrElse("desktop-native/build/bin"))
    systemProperty("java.library.path", nativeDir.absolutePath)
    systemProperty("mockingword.native.dir", nativeDir.absolutePath)
    systemProperty("mockingword.cpuOnly", providers.gradleProperty("desktopCpuOnly").getOrElse("false"))
    // Gradle が UTF-8 で読み取る子プロセスの出力をそろえる。
    systemProperty("stdout.encoding", "UTF-8")
    systemProperty("stderr.encoding", "UTF-8")
    environment("PATH", nativeDir.absolutePath + File.pathSeparator + System.getenv("PATH").orEmpty())
}
