plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "app.plamotranslate.llm"
    compileSdk = 36
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 33  // 性能ヒント（APerformanceHint）がAPI 33から
        // JNI から名前で呼ばれる受け口を、使う側で縮小しても残す
        consumerProguardFiles("consumer-rules.pro")
        ndk {
            abiFilters += "arm64-v8a"
        }
        externalNativeBuild {
            cmake {
                // デバッグビルドでもllama.cppは最適化して作る（Debugだと推論が桁違いに遅い）
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                // llama.cppを複数の共有ライブラリに分けたので、C++の標準ライブラリも共有にする（ライブラリ間で1つにそろえる）
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // 中核（言語の型・翻訳の依頼・プロンプト・エンジンの差し込み口）。app からも見えるよう api にする
    api(project(":core-pipeline"))
    implementation(project(":core-log"))
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

// JDK 21で動かし、Java 17相当のバイトコードを出す（JDK 17は入れていない）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}
