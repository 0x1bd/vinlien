plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.kvxd.vinlien.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.kvxd.vinlien.android"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.android.core.ktx)
    implementation(libs.android.appcompat)
    implementation(libs.android.material)
    implementation(libs.android.media)
    implementation(libs.android.constraintlayout)
    implementation(libs.android.kotlinx.coroutines.android)
}
