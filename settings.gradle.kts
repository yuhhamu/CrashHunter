pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories { mavenCentral() }
}
rootProject.name = "CrashHunter"
include("core", "app", "verifier")
