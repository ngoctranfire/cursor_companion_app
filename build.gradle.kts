// AGP 9's built-in Kotlin bundles KGP 2.2.10. Pin the built-in Kotlin compiler to
// 2.4.0 (matching the Compose/serialization compiler plugins) by raising the KGP on
// the buildscript classpath, as documented for AGP 9 built-in Kotlin. The legacy
// buildscript block needs its own repositories (settings pluginManagement only feeds
// the plugins {} block, and FAIL_ON_PROJECT_REPOS does not apply to buildscript).
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.0")
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}
