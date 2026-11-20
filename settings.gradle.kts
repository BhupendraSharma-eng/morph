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

rootProject.name = "Morph"

// :core  — pure Kotlin agent runtime (providers, agent loop, generative-UI schema). JVM-testable.
// :app   — the Android launcher: Compose surfaces, Android capabilities (tools), voice, settings.
include(":core", ":app")
