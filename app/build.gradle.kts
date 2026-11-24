plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "it.firemuseum"
    compileSdk = 35

    defaultConfig {
        applicationId = "it.firemuseum"
        minSdk = 25          // Fire OS 6 (Fire TV Stick 2a gen / 4K 1a gen) e successivi
        targetSdk = 34
        versionCode = (System.getenv("FM_VERSION_CODE") ?: "1").toInt()
        versionName = System.getenv("FM_VERSION_NAME") ?: "0.1.0"
    }

    // Firma release: in CI dai segreti GitHub (FM_KEYSTORE…), altrimenti chiave di debug.
    val ksPath: String? = System.getenv("FM_KEYSTORE")
    signingConfigs {
        if (ksPath != null) create("release") {
            storeFile = file(ksPath)
            storePassword = System.getenv("FM_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FM_KEY_ALIAS") ?: "firemuseum"
            keyPassword = System.getenv("FM_KEY_PASSWORD") ?: System.getenv("FM_KEYSTORE_PASSWORD")
            storeType = "pkcs12"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (ksPath != null) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { buildConfig = true }
    lint { abortOnError = false; checkReleaseBuilds = true }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
