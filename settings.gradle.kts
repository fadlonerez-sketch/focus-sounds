pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "FocusSounds"

// :core is pure Kotlin (no Android APIs) so the session logic can later be reused
// by a desktop/Windows target without rewriting it.
include(":core")
include(":app")
