plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.streambox.legacy"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.example.streambox.tv"
        minSdk = 19
        targetSdk = 28
        versionCode = 9
        versionName = "0.9.0-tv19-polished"
    }
}

dependencies {
    implementation("com.google.android.exoplayer:exoplayer:2.18.7")
    implementation("com.google.android.exoplayer:extension-rtmp:2.18.7")
    implementation("org.conscrypt:conscrypt-android:2.5.2")
}
