import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
// them. Falls back to "nogit" outside a checkout or when `git rev-parse` fails — without an
// exit-code/regex check, a "fatal: ..." error message would otherwise be embedded in the filename.
val gitShortHash: String = runCatching {
    val process = ProcessBuilder("git", "rev-parse", "--short", "HEAD")
        .directory(rootDir)
        .redirectErrorStream(true)
        .start()
    val output = process.inputStream.bufferedReader().readText().trim()
    val exitCode = process.waitFor()
    output.takeIf { exitCode == 0 && it.matches(Regex("^[0-9a-f]{7,}$")) }
}.getOrNull() ?: "nogit"

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
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.jetbrains.lifecycle.viewmodel)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.jaeckel.urlvault.desktop.MainKt"
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
                bundleID = "com.jaeckel.urlvault"
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
// Use Files.move (which throws on failure) rather than File.renameTo (which silently returns false
// on Windows file locks / AV) so a failed rename fails the task instead of leaving CI to discover
// un-hashed filenames.
for (format in listOf("Dmg", "Msi", "Deb")) {
    tasks.matching { it.name == "package$format" }.configureEach {
        doLast {
            val outputDir = layout.buildDirectory
                .dir("compose/binaries/main/${format.lowercase()}")
                .get().asFile
            val matches = outputDir.listFiles()
                ?.filter { it.extension.equals(format, ignoreCase = true) }
                .orEmpty()
            for (original in matches) {
                if (original.nameWithoutExtension.endsWith("-$gitShortHash")) continue
                val target = File(outputDir, "${original.nameWithoutExtension}-$gitShortHash.${original.extension}")
                Files.move(
                    original.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
        }
    }
}
