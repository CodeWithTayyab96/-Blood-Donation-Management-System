plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.tayyab.blooddonation"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.tayyab.blooddonation"
        minSdk = 26          // Android 8.0 and above
        targetSdk = 34
        versionCode = 4
        versionName = "1.3"

        // Supabase (v1.3): the PostgREST client reads these BuildConfig
        // fields. They come from Gradle project properties of the same
        // names — in CI, from GitHub Secrets SUPABASE_URL and
        // SUPABASE_ANON_KEY (see .github/workflows/release.yml). No key
        // or token value is ever written in this file: with no property
        // set, the URL falls back to this project's public URL and the
        // key falls back to empty, which makes SupabaseClient report
        // "not configured" and the app run fully offline on SQLite.
        val supabaseUrl = (project.findProperty("SUPABASE_URL") as String?)
            ?.trim()?.takeIf { it.isNotBlank() }
            ?: "https://lwxfvxemhboxpoicrpbx.supabase.co"
        val supabaseAnonKey = (project.findProperty("SUPABASE_ANON_KEY") as String?)
            ?.trim() ?: ""
        buildConfigField("String", "SUPABASE_URL", "\"$supabaseUrl\"")
        buildConfigField("String", "SUPABASE_ANON_KEY", "\"$supabaseAnonKey\"")
    }

    // Release signing. The keystore itself is NEVER committed: it lives in
    // GitHub Secrets (see .github/workflows/generate-key.yml and release.yml).
    // The workflow decodes it to app/release.keystore and passes the
    // passwords as -P properties. Without those properties, a release build
    // simply has no signing config, so a local debug build is unaffected.
    val releaseKeystoreFile = file("release.keystore")
    val releaseStorePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
    val releaseKeyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
    val releaseKeyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
    val hasReleaseSigning = releaseKeystoreFile.exists() && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null
    if (hasReleaseSigning) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        viewBinding = true
        // AGP 8 does NOT generate BuildConfig by default. The in-app updater
        // (AppUpdater.kt, MainActivity.kt) reads BuildConfig.VERSION_NAME /
        // VERSION_CODE, so without this the release build fails with
        // "unresolved reference: BuildConfig" — this was the v1.2 run #2 failure.
        buildConfig = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
}
