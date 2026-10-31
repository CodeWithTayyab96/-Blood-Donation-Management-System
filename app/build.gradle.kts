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
        versionCode = 7
        versionName = "1.6"

        // Supabase (v1.3, Auth added in v1.4): the PostgREST client reads these BuildConfig
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

        // Push notifications (v1.7, Firebase Cloud Messaging). The four values
        // come from Gradle project properties of the same names — in CI, from
        // GitHub secrets (never the service-account key: that lives only as a
        // Supabase secret, read by the push-notify Edge Function). With any
        // value missing the app reports Push.notConfigured and keeps the
        // WorkManager polling fallback, exactly like builds before this.
        // Firebase is initialised explicitly in code (Push.kt), so there is no
        // google-services.json and no google-services Gradle plugin to choke CI.
        fun firebaseProp(name: String): String =
            (project.findProperty(name) as String?)?.trim()?.takeIf { it.isNotBlank() } ?: ""
        buildConfigField("String", "FIREBASE_PROJECT_ID", "\"${firebaseProp("FIREBASE_PROJECT_ID")}\"")
        buildConfigField("String", "FIREBASE_APP_ID", "\"${firebaseProp("FIREBASE_APP_ID")}\"")
        buildConfigField("String", "FIREBASE_API_KEY", "\"${firebaseProp("FIREBASE_API_KEY")}\"")
        buildConfigField("String", "FIREBASE_SENDER_ID", "\"${firebaseProp("FIREBASE_SENDER_ID")}\"")
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
        // viewBinding was enabled but never used — every screen still calls
        // findViewById. Removed so the generated binding classes stop costing
        // build time and confusing readers.
        // AGP 8 does NOT generate BuildConfig by default. The in-app updater
        // (AppUpdater.kt, MainActivity.kt) reads BuildConfig.VERSION_NAME /
        // VERSION_CODE, so without this the release build fails with
        // "unresolved reference: BuildConfig" — this was the v1.2 run #2 failure.
        buildConfig = true
    }

    testOptions {
        // Local unit tests run against the android.jar stubs, which throw on
        // every method call. Returning defaults instead means a test that only
        // touches pure Kotlin (BloodCompatibility, AppUpdater.compareVersionNames)
        // never trips over a stub it did not mean to call.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    // EncryptedSharedPreferences for the session store (Auth tokens and the
    // offline password hash). 1.1.0-alpha06 is the release that carries the
    // fixes for the Android 9/10 Keystore issues in 1.0.0.
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // Periodic background polling for urgent blood requests (the fallback).
    // Still the only alerting path when no Firebase values were baked in.
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // Push notifications (v1.7). Deliberately WITHOUT the google-services
    // plugin: Firebase is initialised explicitly from BuildConfig values, so
    // a build without Firebase credentials still compiles and runs offline.
    // 23.x keeps the classic Java FirebaseOptions.Builder
    // (setSenderId/setApiKey); the 24.x line greeted this without-JVM-toolkit
    // setup with API shape changes, so this stays pinned until a full Firebase
    // project is actually wired up end to end.
    implementation("com.google.firebase:firebase-messaging:23.4.1")

    // Local JVM unit tests (app/src/test). No Android runtime needed.
    testImplementation("junit:junit:4.13.2")
}
