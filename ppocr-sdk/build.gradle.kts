plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.paddle.ocr"
    compileSdk = 37

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("proguard-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    implementation(libs.onnxruntime.android)
    // Slim self-built OpenCV AAR (core/imgproc/imgcodecs/java/geometry, arm64-v8a only).
    // compileOnly: library 不能 implementation 本地 AAR,运行时由 app 提供。
    compileOnly(files("${rootDir}/libs/opencv-slim.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
