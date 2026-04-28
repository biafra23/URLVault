// Top-level build file: configuration options common to all sub-projects/modules.

// Override the R8 version bundled with AGP 8.7.3 (R8 ~8.5) with 8.13.19.
// Gradle's buildscript-classpath conflict resolution picks the highest version,
// so AGP automatically uses this newer R8 for `assembleRelease`.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools:r8:8.13.19")
    }
}

plugins {
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.ksp) apply false
}
