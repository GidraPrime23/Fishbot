plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.fishbot"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.fishbot"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toIntOrNull() ?: 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    signingConfigs {
        create("fb") {
            val ksPath = System.getenv("FB_KS_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storeType = "pkcs12"
                storePassword = System.getenv("FB_KS_PASS")
                keyAlias = "fishbot"
                keyPassword = System.getenv("FB_KS_PASS")
            }
        }
    }

    buildTypes {
        debug {
        isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = if (System.getenv("FB_KS_PATH") != null) signingConfigs.getByName("fb") else signingConfigs.getByName("debug")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
}
