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
    namespace = "dev.huenotes"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.huenotes"
        minSdk = 24
        targetSdk = 36
        versionCode = 8
        versionName = "0.8"
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
    testOptions { unitTests.isIncludeAndroidResources = true }
}

dependencies {
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.google.android.gms:play-services-auth:21.2.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.5.0")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
