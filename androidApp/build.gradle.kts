import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.nio.file.Files
import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.ksp)
    alias(libs.plugins.kotlin.serialization)
}

// Allow version to be overridden from the command line (used by the release CI workflow).
// e.g. ./gradlew assembleRelease -PappVersion=0.1.0 -PappVersionCode=2
val appVersion: String = project.findProperty("appVersion")?.toString() ?: "1.0.0"
val appVersionCode: Int = project.findProperty("appVersionCode")?.toString()?.toIntOrNull() ?: 1

android {
    namespace = "com.biafra23.anchorvault.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.biafra23.anchorvault"
        minSdk = 29
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        getByName("debug") {
            // Shared debug keystore checked into the repo so that debug builds
            // are signed identically regardless of build machine, allowing
            // seamless updates without reinstalling.
            storeFile = file("debug.keystore")
            storePassword = project.property("DEBUG_STORE_PASSWORD") as String
            keyAlias = project.property("DEBUG_KEY_ALIAS") as String
            keyPassword = project.property("DEBUG_KEY_PASSWORD") as String
        }
        create("release") {
            // Populated from environment variables injected by the release CI workflow.
            // The keystore is decoded from a base64 secret into a system temp file so it
            // is never placed in the cached build directory, and is deleted on JVM exit.
            val keystoreB64 = System.getenv("ANDROID_KEYSTORE_BASE64")
            if (keystoreB64 != null) {
                val keystoreFile = Files.createTempFile("anchorvault-", ".keystore").toFile()
                keystoreFile.writeBytes(Base64.getDecoder().decode(keystoreB64))
                keystoreFile.deleteOnExit()
                storeFile = keystoreFile
                storePassword = System.getenv("ANDROID_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("ANDROID_KEY_ALIAS") ?: ""
                keyPassword = System.getenv("ANDROID_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val hasSigningSecrets = System.getenv("ANDROID_KEYSTORE_BASE64") != null
            signingConfig = if (hasSigningSecrets) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = JvmTarget.JVM_11.target
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += "NullSafeMutableLiveData"
        checkReleaseBuilds = false
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":shared"))

    // Activity + Compose
    implementation(libs.activity.compose)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // SQLCipher (encrypted database)
    implementation(libs.sqlcipher.android)
    implementation(libs.sqlite.ktx)

    // Encrypted SharedPreferences
    implementation(libs.security.crypto)

    // Lifecycle
    implementation(libs.lifecycle.viewmodel.ktx)

    // Koin
    implementation(libs.koin.android)
    implementation(libs.koin.android.compat)
    implementation(libs.koin.compose)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Serialization
    implementation(libs.kotlinx.serialization.json)
}
