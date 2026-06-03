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

rootProject.name = "frp-android"

include(
    ":app",
    ":core-frp",
    ":core-runtime",
    ":core-data",
    ":core-ui",
    ":feature-dashboard",
    ":feature-profiles",
    ":feature-editor",
    ":feature-logs",
    ":feature-settings",
)
