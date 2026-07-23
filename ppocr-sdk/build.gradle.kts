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
    // compileOnly: local .aar is not bundled into the library AAR; :app provides it at runtime.
    compileOnly(files("${rootDir}/libs/opencv-slim.aar"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
}
