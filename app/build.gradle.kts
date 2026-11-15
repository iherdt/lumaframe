import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

// Release signing inputs, read from env vars (CI) or a local, git-ignored
// keystore.properties (local release builds). Nothing secret is committed.
// See RELEASING.md.
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun releaseSecret(env: String, prop: String): String? =
    System.getenv(env) ?: keystoreProps.getProperty(prop)

android {
    namespace = "com.iherdt.lumaframe"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.iherdt.lumaframe"
        minSdk = 28
        targetSdk = 29
        versionCode = 1
        versionName = "1.0.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Build the existing in-place layout (no file moves during the migration).
    sourceSets["main"].apply {
        manifest.srcFile("AndroidManifest.xml")
        java.setSrcDirs(listOf("src"))
        kotlin.setSrcDirs(listOf("src"))
        res.setSrcDirs(listOf("res"))
        assets.setSrcDirs(listOf("assets"))
    }
    // Plain JVM tests for the Android-free pieces (the QR search).
    sourceSets["test"].apply {
        java.setSrcDirs(listOf("test"))
        kotlin.setSrcDirs(listOf("test"))
    }

    signingConfigs {
        create("release") {
            val ksPath = releaseSecret("LUMAFRAME_KEYSTORE", "storeFile")
            if (ksPath != null && file(ksPath).exists()) {
                storeFile = file(ksPath)
                storePassword = releaseSecret("LUMAFRAME_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = releaseSecret("LUMAFRAME_KEY_ALIAS", "keyAlias")
                keyPassword = releaseSecret("LUMAFRAME_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        // The debug build installs next to a release build (own id), so a change can be tried
        // on a Portal without removing the installed Lumaframe.
        getByName("debug") {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
        }
        getByName("release") {
            // R8: shrink + obfuscate the code and strip unused resources for the release APK.
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            // Sign the release only when a keystore is configured; otherwise
            // assembleRelease still builds, producing an unsigned APK.
            if (signingConfigs.getByName("release").storeFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    lint {
        // The app deliberately targets API 29 for the Meta Portal Go and is
        // sideloaded (not shipped via Google Play), so Play's "target a recent API
        // level" requirement doesn't apply. Don't fail the release build on it.
        disable += "ExpiredTargetSdkVersion"
        // WRITE_SECURE_SETTINGS is a system permission granted once over ADB on Portal
        // (see the manifest comment + ScreensaverGuardService); the "system apps only"
        // lint error is expected and intentional here.
        disable += "ProtectedPermissions"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// Kotlin static analysis. buildUponDefaultConfig uses detekt's default rules; the baseline
// grandfathers pre-existing findings so only new issues fail the build (regenerate with
// `./gradlew detektBaseline`).
detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
    baseline = file("detekt-baseline.xml")
    parallel = true
    // This module uses a flat "src" layout (not src/main/java), so point detekt at it.
    source.setFrom(files("src"))
}

dependencies {
    implementation(files("libs/zxing-core-3.5.3.jar"))

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.exifinterface)

    testImplementation("junit:junit:4.13.2")
}
