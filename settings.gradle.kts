pluginManagement {
    repositories {
        maven { url = uri("/root/localMaven") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositories {
        maven { url = uri("/root/localMaven") }
        google()
        mavenCentral()
    }
}
rootProject.name = "cs"
include(":app")
