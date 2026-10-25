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
        versionCode = 3
        versionName = "1.2"
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
