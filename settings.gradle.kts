pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com[.]android.*")
                includeGroupByRegex("com[.]google.*")
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
        // BRouter (offline routing) is only published via JitPack. Restricted to that one group.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com[.]github[.]abrensch.*") }
        }
    }
}

rootProject.name = "veldash"
include(":app")
