import java.util.Properties

// =====================================================================
// app/build.gradle.kts — build settings for the app itself.
// =====================================================================

plugins {
    // STEP 1: Plugins.
    // AGP 9 has Kotlin support built in, so there is no separate
    // "kotlin-android" plugin any more.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)        // Jetpack Compose compiler
    alias(libs.plugins.kotlin.serialization)  // JSON for the timeline
    alias(libs.plugins.ksp)                   // code generator used by Room
}

android {
    namespace = "com.justvideo.app"
    // Compile against Android 17 (API 37), minor release 2.
    // Newer Compose versions need at least 37.1.
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }

    defaultConfig {
        applicationId = "com.justvideo.app"
        minSdk = 26
        targetSdk = 37
        // STEP 2: Bump versionCode on every build we hand out, so Android
        // accepts it as an update over the previous install.
        versionCode = 2
        versionName = "0.1.1"

        // STEP 2b: Where the in-app updater looks for new builds:
        // the GitHub Releases page of this (public) repository.
        buildConfigField("String", "UPDATE_REPO", "\"justpoqui/justvideo\"")
    }

    // STEP 3: One fixed signing key, kept ONLY on the build machine
    // (the /keystore folder is in .gitignore, so it never goes to GitHub).
    // Every build is signed with the same key, so updates install over the
    // old version without wiping your projects.
    // If the key file isn't there (e.g. a fresh checkout), the build falls
    // back to Android's standard debug key.
    val keyProps = Properties().apply {
        val f = rootProject.file("keystore/keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    val hasSharedKey = rootProject.file("keystore/justvideo.jks").exists() && keyProps.isNotEmpty()
    signingConfigs {
        if (hasSharedKey) {
            create("shared") {
                storeFile = rootProject.file("keystore/justvideo.jks")
                storePassword = keyProps.getProperty("storePassword")
                keyAlias = keyProps.getProperty("keyAlias")
                keyPassword = keyProps.getProperty("keyPassword")
            }
        }
    }
    val appSigning = if (hasSharedKey) signingConfigs.getByName("shared") else signingConfigs.getByName("debug")

    buildTypes {
        // STEP 4: "release" is the build you install.
        // - isMinifyEnabled shrinks the app (smaller, faster).
        // - proguard-rules.pro disables name scrambling so crash reports stay readable.
        // - It is NOT debuggable, which makes Compose run at full speed
        //   (debug builds of Compose are noticeably laggy).
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = appSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        debug {
            signingConfig = appSigning
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true // gives us BuildConfig.VERSION_CODE etc. in code
    }

    // Robolectric UI tests need the app's resources and manifest.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

// STEP 5: Room settings: export the database schema so future upgrades
// can be checked and migrated safely.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    // Core Android + Compose
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // Database
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // JSON + coroutines
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    // Tests that run on the build machine (no phone needed).
    // Robolectric simulates Android, so the real screens can be opened and
    // tapped through automatically.
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.junit)
    testImplementation(platform(libs.compose.bom))
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
}
