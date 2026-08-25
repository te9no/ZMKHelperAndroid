plugins {
    id("com.android.application")
}

android {
    namespace = "io.github.te9no.zmkhelper"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.github.te9no.zmkhelper"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "0.2.0-alpha.2"
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("ANDROID_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("no.nordicsemi.android:dfu:2.9.0")
    implementation("com.github.mik3y:usb-serial-for-android:3.11.0")
}
