plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.xgglass.pfd"
    compileSdk = 36

    dynamicFeatures += setOf(":feature:meta")

    defaultConfig {
        applicationId = "com.example.xgglass.pfd"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime:2.9.4")
    implementation("androidx.savedstate:savedstate:1.3.1")
    implementation("io.github.hkust-spark:xgglass-core:0.3.0")
    implementation("io.github.hkust-spark:xgglass-core-android:0.3.0")
    implementation("io.github.hkust-spark:xgglass-app-contract:0.3.0")
    implementation("io.github.hkust-spark:xgglass-device-even:0.3.0")
    implementation("io.github.hkust-spark:xgglass-device-simulator:0.3.0")
    implementation("com.google.android.play:feature-delivery-ktx:2.1.0")
}
