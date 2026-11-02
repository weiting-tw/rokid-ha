pluginManagement {
    repositories {
        google {
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
        google()
        mavenCentral()
        // Rokid public Maven (official, see docs/SDK_RESEARCH.md §2).
        // Restricted to com.rokid.* so no other dependency can be resolved from it.
        maven {
            url = uri("https://maven.rokid.com/repository/maven-public/")
            content { includeGroupByRegex("com\\.rokid.*") }
        }
    }
}

rootProject.name = "rokid-ha"

include(":app")
include(":rokid")
include(":domain")
include(":homeassistant")
include(":presentation")
include(":data")
