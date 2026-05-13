import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

// Load keystore.properties if present (kept out of git via .gitignore)
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.mtgebay.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mtgebay.app"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // OpenCV ships native libs (~35MB) for arm64-v8a, armeabi-v7a, x86, x86_64.
        // Personal/sideloaded use: ship only arm64-v8a (every consumer Android phone since 2017).
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        if (keystorePropsFile.exists()) {
            create("release") {
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = if (keystorePropsFile.exists()) signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }

    flavorDimensions += "env"
    productFlavors {
        create("sandbox") {
            dimension = "env"
            applicationIdSuffix = ".sandbox"
            versionNameSuffix = "-sandbox"
            buildConfigField("String", "EBAY_BASE_URL", "\"https://api.sandbox.ebay.com\"")
            buildConfigField("String", "EBAY_TRADING_URL", "\"https://api.sandbox.ebay.com/ws/api.dll\"")
            buildConfigField("String", "TOKEN_PREF_KEY", "\"token_sandbox\"")
            buildConfigField("String", "TOKEN_EXPIRY_PREF_KEY", "\"token_sandbox_expiry\"")
        }
        create("prod") {
            dimension = "env"
            buildConfigField("String", "EBAY_BASE_URL", "\"https://api.ebay.com\"")
            buildConfigField("String", "EBAY_TRADING_URL", "\"https://api.ebay.com/ws/api.dll\"")
            buildConfigField("String", "TOKEN_PREF_KEY", "\"token_prod\"")
            buildConfigField("String", "TOKEN_EXPIRY_PREF_KEY", "\"token_prod_expiry\"")
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
        compose = true
        buildConfig = true
    }

    sourceSets {
        getByName("main") {
            kotlin.srcDirs("src/main/kotlin")
        }
        getByName("test") {
            kotlin.srcDirs("src/test/kotlin")
        }
        getByName("androidTest") {
            kotlin.srcDirs("src/androidTest/kotlin")
        }
    }

    androidResources {
        // Keep phash.bin uncompressed so PhashDb can memory-map it directly out of the APK.
        noCompress.add("bin")
    }

    testOptions {
        unitTests {
            // android.util.Log etc. are stubs in the JVM unit-test runtime; return safe
            // defaults instead of throwing so production code can log without forcing
            // every unit test to mock Android framework calls.
            isReturnDefaultValues = true
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "/META-INF/{AL2.0,LGPL2.1}"
            )
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose (BOM-managed versions)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Kotlinx (used app-wide from Phase 3 onward; no harm keeping baseline now)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // OpenCV (Phase 2c+: BitmapPhasher pipeline — grayscale + Lanczos resize)
    implementation(libs.opencv)

    // ML Kit Document Scanner (Phase 2e: edge detection + perspective correction)
    implementation(libs.mlkit.document.scanner)

    // Coil 3 (Phase 3c: card thumbnails from Scryfall image URLs)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Networking (Phase 3a: Scryfall client; Phase 4: TCGTracking; Phase 6: eBay)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Room (Phase 3b: Scryfall card cache; Phase 5: drafts table)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.mockk)
    androidTestImplementation(libs.androidx.room.testing)

    // Instrumented tests
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
