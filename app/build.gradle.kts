plugins {
    alias(libs.plugins.android.application)
    // Kotlin встроен в AGP 9 – отдельный Kotlin-плагин подключать нельзя
}

android {
    namespace = "com.marlendd.remindy"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.marlendd.remindy"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        // Спайк ставится на один физический arm64-телефон; режет нативные библиотеки в APK
        ndk { abiFilters += "arm64-v8a" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.appcompat)
}
