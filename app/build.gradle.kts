import java.time.LocalDate
import java.time.format.DateTimeFormatter

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

// CI release injects INKLEAF_VERSION_NAME / INKLEAF_VERSION_CODE from the git tag.
// Local builds without injection keep simple defaults. Debug always shows fixed "debug".
fun envOrProperty(envName: String, propertyName: String): String? =
    System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: (findProperty(propertyName) as String?)?.takeIf { it.isNotBlank() }

val injectedVersionName = envOrProperty("INKLEAF_VERSION_NAME", "inkleaf.versionName")
val injectedVersionCode =
    envOrProperty("INKLEAF_VERSION_CODE", "inkleaf.versionCode")?.toIntOrNull()

android {
    namespace = "com.exio.inkleaf"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.exio.inkleaf"
        minSdk = 29
        targetSdk = 37
        versionCode = injectedVersionCode ?: 1
        versionName = injectedVersionName ?: "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
    }
    packaging {
        jniLibs {
            // Pdfium bundles the 16 KB-aligned C++ runtime. Keep one shared copy.
            pickFirsts += "lib/**/libc++_shared.so"
        }
    }
}

// Debug: separate package; About shows a fixed "debug" version string.
// APK file name carries the git commit hash + build date to tell builds apart.
val gitShortHash = providers.exec {
    commandLine("git", "rev-parse", "--short", "HEAD")
    isIgnoreExitValue = true
}.standardOutput.asText.getOrElse("nogit").trim()
val buildDate = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE)

androidComponents {
    onVariants(selector().withBuildType("debug")) { variant ->
        variant.applicationId.set("com.exio.inkleaf.debug")
        variant.applicationId.finalizeValue()
        variant.outputs.forEach { output ->
            output.versionName.set("debug")
            output.versionName.finalizeValue()
            output.outputFileName.set("inkleaf-debug-$gitShortHash-$buildDate.apk")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.appcompat)
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
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.material.kolor)
    implementation(libs.okhttp)
    implementation(libs.okhttp.dnsoverhttps)
    implementation(libs.debugoverlay)
    implementation(libs.debugoverlay.okhttp)
    implementation(libs.debugoverlay.timber)
    implementation(libs.timber)

    // The plugin runtime is feature-gated at startup and fails closed when the WebView lacks it.
    implementation(libs.androidx.javascriptengine)

    implementation(libs.coil.compose)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.paging)
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)
    implementation(libs.ahmer.pdfium)
    implementation(libs.reorderable)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.paging.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
