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

            if (keystorePassword != null &&
                keyAliasValue != null &&
                keyPasswordValue != null
            ) {
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
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(project(":core:domain"))
    implementation(project(":core:strategy"))
    implementation(project(":core:execution"))
    implementation(project(":core:marketdata"))
    implementation(project(":strategy-engine"))
    implementation(project(":backtest"))
    implementation(project(":data"))

    // JVM unit tests for BacktestJobStore (cancel / delete / reload rules).
    // The real org.json replaces the android.jar stubs, which are not functional on the JVM.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
