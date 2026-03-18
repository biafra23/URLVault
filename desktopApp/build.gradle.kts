import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop")

    sourceSets {
        val desktopMain by getting

        desktopMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.desktop.currentOs)
            implementation(libs.kotlinx.coroutines.swing)
            implementation(libs.sqlite.jdbc)
            implementation(libs.slf4j.simple)
            implementation(libs.koin.core)
            implementation(libs.ktor.client.java)
        }
    }
}

compose.desktop {
    application {
        mainClass = "com.biafra23.anchorvault.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "AnchorVault"
            packageVersion = "1.0.0"
            description = "Secure bookmark storage with Bitwarden sync"
            copyright = "© 2024 AnchorVault"

            macOS {
                iconFile.set(project.file("src/main/resources/icon.icns"))
                bundleID = "com.biafra23.anchorvault"
            }
            windows {
                iconFile.set(project.file("src/main/resources/icon.ico"))
                menuGroup = "AnchorVault"
                upgradeUuid = "e0f7a5e3-4d2b-4b8e-9c1a-5f6d7e8a9b0c"
            }
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
            }
        }
    }
}
