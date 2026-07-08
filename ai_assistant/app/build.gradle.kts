plugins {
    id("com.android.application")
}

import java.util.Properties

val localProps = Properties()
val localPropsFile = rootProject.file("local.properties")
if (localPropsFile.exists()) {
    localPropsFile.inputStream().use { localProps.load(it) }
}

fun propOrEnv(key: String, envKey: String, defaultValue: String = ""): String =
    (localProps.getProperty(key) ?: System.getenv(envKey) ?: defaultValue).trim()

fun escapeForBuildConfig(value: String): String =
    value.replace("\\", "\\\\").replace("\"", "\\\"")

val aiBaseUrl = propOrEnv("ai.baseUrl", "AI_BASE_URL")
val aiApiKey = propOrEnv("ai.apiKey", "AI_API_KEY")
val aiModel = propOrEnv("ai.model", "AI_MODEL", "gpt-4o-mini")

android {
    namespace = "com.example.xgglass.aiassistant"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.example.xgglass.aiassistant"
        minSdk = 29
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        buildConfigField("boolean", "XG_SIMULATOR", "false")
        buildConfigField("String", "XG_SIM_VIDEO_PATH", "\"\"")
        buildConfigField("String", "AI_BASE_URL", "\"${escapeForBuildConfig(aiBaseUrl)}\"")
        buildConfigField("String", "AI_API_KEY", "\"${escapeForBuildConfig(aiApiKey)}\"")
        buildConfigField("String", "AI_MODEL", "\"${escapeForBuildConfig(aiModel)}\"")
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    implementation("io.github.hkust-spark:xgglass-core:0.3.0")
    implementation("io.github.hkust-spark:xgglass-core-android:0.3.0")
    implementation("io.github.hkust-spark:xgglass-device-simulator:0.3.0")
}
