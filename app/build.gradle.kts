plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.aster.mobile"
    compileSdk = 35
    buildFeatures { buildConfig = true }
    defaultConfig {
        applicationId = "app.aster.mobile"
        minSdk = 28
        targetSdk = 35
        versionCode = 1
        versionName = "4.0.0-preview"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    val signingFile = System.getenv("ASTER_ANDROID_KEYSTORE")
    if (!signingFile.isNullOrBlank()) {
        signingConfigs.create("ownerRelease") {
            storeFile = file(signingFile)
            storePassword = System.getenv("ASTER_ANDROID_STORE_PASSWORD")
            keyAlias = System.getenv("ASTER_ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ASTER_ANDROID_KEY_PASSWORD")
        }
    }
    buildTypes {
        getByName("debug") { applicationIdSuffix = ".debug"; versionNameSuffix = "-debug" }
        getByName("release") {
            isMinifyEnabled = false
            if (!signingFile.isNullOrBlank()) signingConfig = signingConfigs.getByName("ownerRelease")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isReturnDefaultValues = true }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test:rules:1.6.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
