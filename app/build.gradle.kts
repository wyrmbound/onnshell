import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.xplsh.onnshell"
    compileSdk = 36
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.xplsh.onnshell"
        minSdk = 34
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        // Daily build for the box: optimized like release (R8, not debuggable) so cold start is real,
        // but signed with the debug key and testOnly (src/fast/AndroidManifest.xml), so it updates
        // over debug builds and "dpm remove-active-admin" still works. Phase 6 adds the real release.
        create("fast") {
            initWith(getByName("release"))
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures { compose = true; aidl = true }
    // Shizuku's bridge process loads libbufbridge.so by file path, so the lib must exist on disk, not only inside the APK.
    packaging { jniLibs { useLegacyPackaging = true } }

    // M4 zero-copy bridge: tiny JNI run inside the Shizuku user service (shell uid). Both ABIs:
    // the box's zygote is 32-bit, but Shizuku's service process bitness is confirmed at runtime.
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } }
    defaultConfig.ndk { abiFilters += listOf("armeabi-v7a", "arm64-v8a") }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.06.00"))
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.tv:tv-material:1.0.0")
    // Shizuku: run commands as the shell user (closing apps; the touchpad in Phase 4)
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
    // adb client: OnnShell starts Shizuku itself at boot through the box's own adbd (127.0.0.1:5555)
    implementation("dev.mobile:dadb:1.2.10")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303") // android.jar only has stubs in JVM tests
}
