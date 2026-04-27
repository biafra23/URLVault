pluginManagement {
    repositories {
        maven("https://maven.google.com/") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.google.com/")
        mavenCentral()
        // Liquid AI Leap SDK is published only as snapshots as of 2026-04.
        // Restricted to ai.liquid.* so we don't accidentally resolve other
        // dependencies from snapshot bytes that can change without notice.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
            content {
                includeGroup("ai.liquid.leap")
            }
        }
    }
}

rootProject.name = "URLVault"

include(":shared")
include(":androidApp")
include(":desktopApp")
