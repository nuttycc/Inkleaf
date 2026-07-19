plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
}

val releaseSigningValues = listOf(
    "INKLEAF_KEYSTORE_FILE",
    "INKLEAF_KEYSTORE_PASSWORD",
    "INKLEAF_KEY_ALIAS",
    "INKLEAF_KEY_PASSWORD",
).associateWith { System.getenv(it) }
val hasAnyReleaseSigningValue = releaseSigningValues.values.any { !it.isNullOrBlank() }
val hasReleaseSigning = releaseSigningValues.values.all { !it.isNullOrBlank() }

if (hasAnyReleaseSigningValue && !hasReleaseSigning) {
    error("INKLEAF_KEYSTORE_FILE, INKLEAF_KEYSTORE_PASSWORD, INKLEAF_KEY_ALIAS, and INKLEAF_KEY_PASSWORD must be set together.")
}

android {
    namespace = "com.exio.inkleaf"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "com.exio.inkleaf"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }

        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_STL=c++_static"
                targets += "inkleaf_enhancement"
            }
        }
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseSigningValues.getValue("INKLEAF_KEYSTORE_FILE")!!)
                storePassword = releaseSigningValues.getValue("INKLEAF_KEYSTORE_PASSWORD")
                keyAlias = releaseSigningValues.getValue("INKLEAF_KEY_ALIAS")
                keyPassword = releaseSigningValues.getValue("INKLEAF_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            ndk {
                abiFilters += listOf("armeabi-v7a", "x86", "x86_64")
            }
        }
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Pdfium and OpenCV both bundle a 16 KB-aligned C++ runtime. Keep one shared copy;
            // verify the selected library in the next manually built release artifact.
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.30.5"
        }
    }
}

dependencies {
    implementation(project(":ppocr-sdk"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.composables.material.symbols.outlined.android)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.exifinterface)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material.kolor)

    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.ahmer.pdfium)
    implementation(libs.reorderable)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.opencv.android)

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
