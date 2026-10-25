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
    }
}

rootProject.name = "forkcast"

include(":app")
include(":core:model")
include(":core:domain")
include(":core:data")
include(":core:network")
// Lint checks of our own (hardcoded user-facing text).
include(":lint-checks")
// Generates the Baseline Profile (app/src/githubRelease/generated/baselineProfiles/) on the emulator.
include(":baselineprofile")

// Development only: a stand-in for Samsung Health that writes test data into the emulator's
// Health Connect (another app's data origin). Not part of CI; build with -PwithHcSeed.
if (providers.gradleProperty("withHcSeed").isPresent) include(":tools:hcseed")
