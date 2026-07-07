plugins {
    id("com.android.dynamic-feature")
}

android {
    namespace = "com.example.xgglass.pfd.feature.meta"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }
}

dependencies {
    implementation(project(":app"))
    implementation("io.github.hkust-spark:xgglass-device-meta:0.2.1")
}
