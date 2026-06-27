import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.metro)
}

// Release signing is opt-in: present only when keystore.properties exists
// (it and the .jks are gitignored). Fresh clones / CI still build debug fine.
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "com.vibecode.companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.vibecode.companion"
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

// AGP 9 built-in Kotlin registers the standard `kotlin` extension; configure the
// compiler through its compilerOptions block (the old android.kotlinOptions DSL is
// gone). jvmTarget would otherwise default to compileOptions.targetCompatibility; we
// pin it explicitly to keep the two in lockstep.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// Metro DI (compiler plugin). Low-overhead tracing stays on from day one; the
// heavier graph reports (generateMetroGraphMetadata / analyzeMetroGraph /
// generateMetroGraphHtml) are CLI-gated — they only register when
// reportsDestination is present, e.g.:
//   ./gradlew :app:generateMetroGraphHtml -Pmetro.reportsDestination=metro/reports --rerun-tasks
metro {
    traceDestination.set(layout.buildDirectory.dir("metro/traces"))
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.okhttp.logging)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    // -compose pulls in metrox-viewmodel transitively (via `api`).
    implementation(libs.metrox.viewmodel.compose)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
