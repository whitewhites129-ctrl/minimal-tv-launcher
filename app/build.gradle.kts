plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "br.com.launcher.tv"
    compileSdk = 35
    defaultConfig {
        applicationId = "br.com.launcher.tv"
        minSdk = 23
        targetSdk = 28
        versionCode = 1
        versionName = "0.1.0"
    }
}
