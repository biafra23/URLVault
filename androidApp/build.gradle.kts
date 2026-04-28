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

// Short git commit hash, baked into artifact filenames. Falls back to "nogit" outside a checkout
// or when `git rev-parse` fails — without an exit-code/regex check, a "fatal: ..." error message
// from a non-git environment would otherwise be embedded in the filename.
val gitShortHash: String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    output.takeIf { exitCode == 0 && it.matches(Regex("^[0-9a-f]{7,}$")) }
}.getOrNull() ?: "nogit"

android {
    namespace = "com.jaeckel.urlvault.android"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.jaeckel.urlvault"
        minSdk = 31
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersion

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Llamatik ships native libs for arm64-v8a, armeabi-v7a, x86, x86_64.
        // libllama_jni.so alone is ~23 MB per ABI; restricting to arm64-v8a cuts
        // ~90 MB of unused code from the APK. Every supported Android device
        // (minSdk 31) is arm64-v8a-capable. Add x86_64 here if you need emulator
        // support for local development.
        ndk {
            abiFilters += "arm64-v8a"
        }
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
                val keystoreFile = Files.createTempFile("urlvault-", ".keystore").toFile()
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

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    lint {
        disable += "NullSafeMutableLiveData"
        abortOnError = false
        checkDependencies = false
    }

    // Use the public ApkVariantOutput interface (legacy applicationVariants API). The modern
    // androidComponents.onVariants Variant API is preferable in principle, but in AGP 8.7.3
    // the VariantOutput interface does not expose outputFileName — that property still lives
    // on the legacy ApkVariantOutput type, which is at least public (vs. the internal
    // BaseVariantOutputImpl). Revisit when AGP exposes outputFileName on VariantOutput.
    applicationVariants.all {
        val variant = this
        outputs.all {
            (this as com.android.build.gradle.api.ApkVariantOutput).outputFileName =
                "URLVault-${appVersion}-${gitShortHash}-${variant.buildType.name}.apk"
        }
    }
}

// Workaround: Compose lint detectors (RememberInCompositionDetector,
// FrequentlyChangingValueDetector) throw IncompatibleClassChangeError on
// Kotlin 2.1.0 + AGP 8.7.3. Disabling the check IDs is not enough because
// the crash occurs during class loading before the check can be skipped.
// Disable lint entirely until the upstream issue is resolved.
project.afterEvaluate {
    tasks.matching { it.name.startsWith("lint") }.configureEach {
        enabled = false
    }
}

// LeapSDK transitively pulls androidx.core 1.17.0 (and a few related
// AndroidX 1.x bumps) which require AGP 8.9.1+. We're on AGP 8.7.3.
// Force-pin to 1.16.0, which is the highest version compatible with
// AGP 8.7.3; 1.13.1 (the previous pin) caused a NoSuchMethodError
// (getCutoutPath) in Compose Foundation Layout 1.10.0.
configurations.configureEach {
    resolutionStrategy.eachDependency {
        // core-viewtree only exists on 1.0.x, leave it alone.
        if (requested.group == "androidx.core" &&
            requested.name in setOf("core", "core-ktx")) {
            useVersion("1.16.0")
        }
        if (requested.group == "androidx.compose.ui" && requested.version == "1.9.0") {
            useVersion("1.7.6")
        }
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

    // Ktor (HTTP client for web page content fetching + Bitwarden sync)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)

    // ML Kit GenAI Prompt API (Gemini Nano on-device)
    implementation(libs.mlkit.genai.prompt)

    // Liquid AI Leap SDK — runs LFM2 family models with grammar-constrained
    // JSON output (used by LeapModelProvider for the LFM2-1.2B-Extract entry).
    implementation(libs.leap.sdk)
    implementation(libs.leap.model.downloader)

    // Llamatik (llama.cpp via JNI; backs LlamatikNativeBridge).
    // Local build from `ferranpons/Llamatik` main (closest tag: v1.1.1) with
    // two patches on top — see filename suffix `+localfix`:
    //   1. Defensive catch around the JSON-stream JNI entries — fixes the
    //      SIGABRT in `nativeGenerateJsonStream` reported as Llamatik#90.
    //   2. Renamed llama.cpp shared libraries (libllamatik_llama.so etc.) so
    //      this AAR can coexist in the same APK with another SDK (e.g.
    //      Leap, ai.liquid.leap:leap-sdk-android) that bundles its own
    //      libllama.so / libggml*.so without filename collisions.
    // Swap back to `libs.llamatik` once both fixes are published upstream.
    implementation(files("libs/llamatik-v1.1.1-main+localfix.aar"))
}
