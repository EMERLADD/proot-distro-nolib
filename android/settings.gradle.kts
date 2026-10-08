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

rootProject.name = "pr"
include(":proot-engine")
if (!providers.gradleProperty("pdnEngineOnly").orNull.toBoolean()) {
    include(":app")
    include(":termlib")
}
