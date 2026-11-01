plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.jpcaptions"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.jpcaptions"
        minSdk = 29
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
        ndk { abiFilters.addAll(listOf("arm64-v8a", "armeabi-v7a")) }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation(files("libs/sherpa-onnx-2.0.1.aar"))
}
