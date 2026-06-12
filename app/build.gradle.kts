plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
}

android {
    namespace = "com.exio.comicreader"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.exio.comicreader"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            // debug 密钥签名：个人自用直装（adb install）。与 Studio
            // "profileable" 运行装的包同钥，可覆盖安装不丢数据。
            // 若将来上架商店，这行要换成正式签名配置
            signingConfig = signingConfigs.getByName("debug")
            // R8 开删码/优化/资源收缩，但不混淆（-dontobfuscate，见
            // proguard-rules.pro 的取舍说明）。首次进页的卡顿一半来自
            // "代码冷"——R8 让要加载的冷代码更少更快。
            //
            // 刻意不用新 DSL `optimization { enable = true }`：AGP 9.2 把它
            // 门控在实验性 flag android.r8.gradual.support 后面（走的是
            // 未毕业的"渐进式 R8"新管线，见 AGP 源码 BooleanOption.R8_GRADUAL_API），
            // 经典属性才是当前的稳定通道。等该 flag 毕业后再迁移
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                // AGP 9 起只支持 optimize 版默认规则文件
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
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
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

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}