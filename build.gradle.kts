plugins {
    id("com.android.application") version "9.4.1" apply false
    id("org.jetbrains.kotlin.android") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.20" apply false
    // v2.22.81: the SukiSU vendor (.71) uses @Serializable and
    // @Parcelize throughout; the manual pipeline passes both
    // compiler plugins to kotlinc directly, Gradle needs them
    // declared or every vendored file fails to compile.
    id("org.jetbrains.kotlin.plugin.serialization") version "2.4.20" apply false
    id("org.jetbrains.kotlin.plugin.parcelize") version "2.4.20" apply false
}
