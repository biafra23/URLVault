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
    }
}

rootProject.name = "URLVault"

include(":shared")
include(":androidApp")
include(":desktopApp")
