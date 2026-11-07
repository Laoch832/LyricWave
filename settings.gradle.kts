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

rootProject.name = "LyricWave"
include(":app", ":host-wechat")
include(":backdrop", ":glass")
include(":core", ":host-api", ":hook-runtime", ":host-applemusic")
include(":plugin-api", ":plugin-runtime")
