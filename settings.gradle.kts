// =====================================================================
// settings.gradle.kts
// Tells Gradle where to download plugins/libraries from and which
// modules belong to this project (we only have one: ":app").
// =====================================================================

pluginManagement {
    repositories {
        google()
        // Google's mirror of Maven Central (same files, often faster/more reliable).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // Libraries may only come from these repositories (safer, more predictable builds).
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's mirror of Maven Central (same files, often faster/more reliable).
        maven("https://maven-central.storage-download.googleapis.com/maven2/")
        mavenCentral()
    }
}

rootProject.name = "JustVideo"
include(":app")
