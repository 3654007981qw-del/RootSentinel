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
        // v2.22.81: libsu (com.github.topjohnwu.libsu) is
        // published only on JitPack.
        maven { url = uri("https://jitpack.io") }
    }
}
rootProject.name = "cs"
include(":app")
