plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.algotrader.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.algotrader.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        create("release") {
            val keystorePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            val keyAliasValue = System.getenv("ANDROID_KEY_ALIAS")
            val keyPasswordValue = System.getenv("ANDROID_KEY_PASSWORD")

            if (keystorePassword != null && keyAliasValue != null && keyPasswordValue != null) {
                storeFile = rootProject.file("keystore/algotrader-release.jks")
                storePassword = keystorePassword
                keyAlias = keyAliasValue
                keyPassword = keyPasswordValue
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:execution"))
    implementation(project(":core:marketdata"))
    implementation(project(":strategy-engine"))
    implementation(project(":backtest"))
    implementation(project(":data"))
}
