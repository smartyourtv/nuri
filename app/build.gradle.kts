plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    alias(libs.plugins.jetbrains.kotlin.serialization)
}

android {
    namespace = "com.app.nuri"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.app.nuri"
        // Nuri floor (brief §10): Samsung Galaxy S10-or-newer = API 29.
        // Deliberately targets old phones; interface freeze starts here.
        minSdk = 29
        targetSdk = 35
        versionCode = 5
        versionName = "0.3.2"

        // Phones only: the floor device is a Galaxy S10-class ARM phone.
        // Without this, ML Kit's bundled models ship native libraries for
        // x86/x86_64 too and the APK balloons past 110 MB; ARM-only keeps
        // the install small for a senior's phone.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }
    }

    // Signing mirrors Scout's chain: env-driven release config, debug key
    // for scaffold builds. Release timing/distribution is Shawn's call.
    val keystoreFilePath = System.getenv("KEYSTORE_FILE_PATH")
    val keystorePassword = System.getenv("KEYSTORE_PASSWORD")
    val keystoreKeyAlias = System.getenv("KEYSTORE_KEY_ALIAS")
    val keystoreKeyPassword = System.getenv("KEYSTORE_KEY_PASSWORD")
    val hasSigningConfig = !keystoreFilePath.isNullOrEmpty() &&
        !keystorePassword.isNullOrEmpty() &&
        !keystoreKeyAlias.isNullOrEmpty() &&
        !keystoreKeyPassword.isNullOrEmpty()

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(keystoreFilePath)
                storePassword = keystorePassword
                keyAlias = keystoreKeyAlias
                keyPassword = keystoreKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasSigningConfig) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug { }
    }

    // Product flavors for the public/private split (brief §6). SCAFFOLD ONLY:
    // both flavors are identical today — stealth code is GATED and will live
    // only in src/private when implemented. Never add stealth to src/main.
    flavorDimensions += "edition"
    productFlavors {
        // UPDATE_FEED_URL (brief §10): each variant checks its OWN update
        // feed. Blank = not wired yet — the distribution endpoint is an open
        // decision (brief §6), and the executor honestly reports that it
        // cannot check rather than pretending. Set per flavor at release
        // time; never point a flavor at the other flavor's feed.
        create("public") {
            dimension = "edition"
            buildConfigField("String", "UPDATE_FEED_URL", "\"\"")
            buildConfigField("boolean", "IS_PRIVATE_BUILD", "false")
        }
        create("private") {
            dimension = "edition"
            buildConfigField("String", "UPDATE_FEED_URL", "\"\"")
            buildConfigField("boolean", "IS_PRIVATE_BUILD", "true")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.androidxComposeCompiler.get()
    }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    // Legal assets (v0.3.2, notices-wiring checklist §2/§5):
    // src/main/assets/THIRD-PARTY-NOTICES must stay a verbatim copy of
    // the master at goals/senior-ai-companion-app/briefs/THIRD-PARTY-NOTICES,
    // and src/main/assets/TERMS.md a copy of the repo-root TERMS.md.
    // Re-copy both whenever a master changes — the in-app Licenses and
    // Terms screens read the assets, and Apache-2.0 §4 requires the
    // notices text to travel inside the distributed APK.
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.security.crypto)
    // Show Nuri (v0.3.0): bundled on-device ML Kit models — OCR + image
    // labels. No network use; the cloud fallback is a separate consented seam.
    implementation(libs.mlkit.text.recognition)
    implementation(libs.mlkit.image.labeling)
    implementation(libs.mlkit.barcode.scanning)
    // NOTE: sherpa-onnx AAR (app/libs/, Scout pattern) is added when the TTS
    // engine lands with the gated voice work — see SCAFFOLD.md. Not copied
    // into the scaffold: 56 MB native lib, and the voice build is gated.
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
