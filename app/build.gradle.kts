import java.io.File
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
}

android {
    namespace = "com.zcc09.hermespocket"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.zcc09.hermespocket"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.6.0"
    }

    buildFeatures {
        buildConfig = true
    }

    signingConfigs {
        // Only a real signing identity when the CI secrets are present; a clone
        // without them still builds a release APK (debug-signed).
        val keystoreB64: String? = System.getenv("KEYSTORE_BASE64")
        if (!keystoreB64.isNullOrBlank()) {
            create("release") {
                val keystore = File(layout.buildDirectory.get().asFile, "hermes-pocket-release.p12")
                keystore.parentFile?.mkdirs()
                keystore.writeBytes(Base64.getMimeDecoder().decode(keystoreB64))
                storeFile = keystore
                storeType = "pkcs12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASS")
                    ?: ""
                keyAlias = System.getenv("KEY_ALIAS") ?: "hermespocket"
                keyPassword = System.getenv("KEY_PASSWORD")
                    ?: System.getenv("KEYSTORE_PASS")
                    ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Forks without signing secrets still build a release APK (debug-signed).
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("dev.rikka.shizuku:api:13.1.5")
    // The ContentProvider the manifest declares ships in its own artifact; without
    // it Android cannot instantiate it and the app crashes before its first frame.
    implementation("dev.rikka.shizuku:provider:13.1.5")
    testImplementation("junit:junit:4.13.2")
}
