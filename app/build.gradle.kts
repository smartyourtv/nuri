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
        versionCode = 9
        versionName = "0.5.0"

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
    // Voice (v0.4.0): sherpa-onnx AAR (app/libs/, the phone Scout
    // artifact, version-pinned 1.13.2 — ONE pinned version; the
    // Lifewave 1.13.8 skew lesson is that the version is recorded,
    // never drifted). Its native libs are restricted to ARM by the
    // abiFilters above. Voice MODELS are NOT in the APK — they
    // download after install, hash-verified (VoiceModelManager).
    implementation(files("libs/sherpa-onnx-1.13.2.aar"))
    // Tar/bzip2 for the model packs (same version Scout ships).
    implementation(libs.commons.compress)
    // Remote observation (v0.5.0, design §13): Firestore relay +
    // anonymous auth + FCM token registration. BoM-pinned. The SDK is
    // inert until FirebaseInit runs (a configured project + a
    // non-Independent mode); Independent installs never initialize it.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.firestore)
    implementation(libs.firebase.auth)
    implementation(libs.firebase.messaging)
    // QR rendering for the pairing offer (design §4.2). Encoding only;
    // scanning is ML Kit barcode (already a dependency).
    implementation(libs.zxing.core)
    // Amendment 2+3 calls: WebRTC video+audio (design §17.1). The
    // peer-connection factory is only initialized while a call runs.
    implementation(libs.webrtc.sdk)
    // System scan UI for the connect code (design §4.2) — no camera
    // pipeline of our own; the OS scanner returns the text.
    implementation(libs.play.services.code.scanner)
    debugImplementation(libs.androidx.ui.tooling)
    testImplementation(libs.junit)
}
