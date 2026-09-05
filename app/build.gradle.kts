plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.ramy.quranradiotv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.ramy.quranradiotv"
        minSdk = 21
        targetSdk = 34
        versionCode = 3
        versionName = "1.4.1"

        // Real phones, tablets and television boxes are all ARM. The speech
        // runtime alone carries twenty megabytes per architecture, so a release
        // ships only these two; debug adds the emulator's below.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        release {
            // Every release so far has been signed with the local debug key, so
            // the release build keeps using it: a new key would refuse to install
            // over the copies people already have.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }

        // Only so the app can be run on an x86 emulator during development;
        // these architectures are deliberately absent from a release.
        debug {
            ndk {
                abiFilters += listOf("x86", "x86_64")
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

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.3.1")
    implementation("androidx.media3:media3-session:1.3.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.3.1")

    // Runs the Whisper speech model for the Surah / Ayah display. The runtime
    // ships its native code for every ABI; the model itself is an optional
    // download, never part of the APK.
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")

    testImplementation("junit:junit:4.13.2")
    // The real org.json, since the Android stub throws from every method.
    testImplementation("org.json:json:20240303")
    // The same runtime for the JVM, so the recogniser can be tested on a
    // workstation against real clips.
    testImplementation("com.microsoft.onnxruntime:onnxruntime:1.22.0")
}

// Set WHISPER_MODEL_DIR to a directory holding the downloaded model files and
// a wav/ folder of 16 kHz clips to run the end-to-end transcription test.
tasks.withType<Test>().configureEach {
    systemProperty("whisper.model.dir", System.getenv("WHISPER_MODEL_DIR") ?: "")
    maxHeapSize = "2g"
}
