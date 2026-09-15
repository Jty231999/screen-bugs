plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bt.flyprank"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bt.flyprank"
        minSdk = 26
        targetSdk = 35
        // 每次改动想触发"新装/更新后自动放满虫子"时把 versionCode 加一：
        // MainActivity 用 versionCode 变化来判断是不是刚装/刚更新
        versionCode = 2
        versionName = "1.1"
    }

    buildTypes {
        release {
            // 恶搞小工具，不做混淆，方便你自己改代码重打包
            isMinifyEnabled = false
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
}
