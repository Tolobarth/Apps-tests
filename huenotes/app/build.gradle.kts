plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    signingConfigs {
        // Fixed debug key (public, not a secret): keeps the SHA-1 stable for Google sign-in and lets updates install over older builds.
        getByName("debug") {
            storeFile = file("debug.keystore"); storePassword = "android"; keyAlias = "androiddebugkey"; keyPassword = "android"
        }
    }
    // Release signing comes from the environment so no secret is ever committed (see docs/store/release-signing.md).
    System.getenv("HUENOTES_KEYSTORE")?.let { ks ->
        signingConfigs.create("release") {
            storeFile = file(ks); storePassword = System.getenv("HUENOTES_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("HUENOTES_KEY_ALIAS"); keyPassword = System.getenv("HUENOTES_KEY_PASSWORD")
        }
        buildTypes.getByName("release").signingConfig = signingConfigs.getByName("release")
    }
    namespace = "dev.huenotes"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.huenotes"
        minSdk = 26          // Android 8+: ML Kit speech needs it
        targetSdk = 36
        versionCode = 24
        versionName = "0.24"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
    // ML Kit's translation engine is a large native library per CPU type. Sideloaded APKs are split per CPU (phones need
    // arm64-v8a; armeabi-v7a for old 32-bit ones); the Play App Bundle does this split on its own.
    splits { abi { isEnable = true; reset(); include("arm64-v8a", "armeabi-v7a"); isUniversalApk = false } }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    implementation("androidx.work:work-runtime:2.10.0")
    implementation("com.google.mlkit:translate:17.0.3")       // on-device translation; language packs downloaded on first use
    implementation("com.google.mlkit:language-id:17.0.6")     // detects a note's language (model bundled, ~1 MB)
    implementation("com.google.mlkit:genai-speech-recognition:1.0.0-alpha1")  // transcribes recordings via Google's speech app
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("com.google.android.gms:play-services-mlkit-text-recognition:19.0.1")  // reads text in photos; model via Play services
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
