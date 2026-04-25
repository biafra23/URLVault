import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

// Allow version to be overridden from the command line (used by the release CI workflow).
// e.g. ./gradlew packageDeb -PappVersion=0.1.0
val appVersion: String = project.findProperty("appVersion")?.toString() ?: "1.0.0"

// Short git commit hash, appended to packaged installer filenames after Compose Desktop produces
// them. Falls back to "nogit" outside a checkout.
val gitShortHash: String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
}.getOrNull()?.takeIf { it.isNotBlank() } ?: "nogit"

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        desktopMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.sqlite.jdbc)
            implementation(libs.slf4j.simple)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.java)
            implementation(libs.jetbrains.lifecycle.viewmodel)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.biafra23.urlvault.desktop.MainKt"
        from(kotlin.targets["desktop"])

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            modules("java.sql", "java.net.http", "java.logging", "java.naming", "jdk.crypto.ec", "jdk.unsupported")
            packageName = "URLVault"
            packageVersion = appVersion
            description = "Secure bookmark storage with Bitwarden sync"
            copyright = "© 2024 URLVault"

            macOS {
                iconFile.set(project.file("src/desktopMain/resources/icon.icns"))
                bundleID = "com.biafra23.anchorvault"
            }
            windows {
                iconFile.set(project.file("src/desktopMain/resources/icon.ico"))
                menuGroup = "URLVault"
                upgradeUuid = "e0f7a5e3-4d2b-4b8e-9c1a-5f6d7e8a9b0c"
            }
            linux {
                iconFile.set(project.file("src/desktopMain/resources/icon.png"))
            }
        }
    }
}

// Compose Desktop's packageVersion must match strict per-platform version formats, so the commit
// hash cannot be part of it. Append it to the produced installer filenames instead.
listOf("Dmg", "Msi", "Deb").forEach { format ->
    tasks.matching { it.name == "package$format" }.configureEach {
        doLast {
            val outputDir = layout.buildDirectory
                .dir("compose/binaries/main/${format.lowercase()}")
                .get().asFile
            outputDir.listFiles { f -> f.extension.equals(format, ignoreCase = true) }
                ?.forEach { file ->
                    if (file.nameWithoutExtension.endsWith("-$gitShortHash")) return@forEach
                    val renamed = java.io.File(outputDir, "${file.nameWithoutExtension}-$gitShortHash.${file.extension}")
                    if (renamed.exists()) renamed.delete()
                    file.renameTo(renamed)
                }
        }
    }
}
