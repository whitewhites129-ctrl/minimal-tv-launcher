plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

android {
    namespace = "br.com.launcher.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "br.com.launcher.tv"
        minSdk = 23
        targetSdk = 28
        versionCode = 17
        versionName = "0.7.0-beta.4"
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }

    val releaseStoreFile = System.getenv("ANDROID_KEYSTORE_PATH")
    val releaseStorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
    val releaseKeyAlias = System.getenv("ANDROID_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("ANDROID_KEY_PASSWORD")

    signingConfigs {
        if (!releaseStoreFile.isNullOrBlank()) {
            create("persistentRelease") {
                storeFile = file(releaseStoreFile)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            if (!releaseStoreFile.isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("persistentRelease")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}