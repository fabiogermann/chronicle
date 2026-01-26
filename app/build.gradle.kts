import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.play.publisher)
    id("kotlin-parcelize")
    id("kotlin-kapt") // Still needed for data binding
    id("com.google.android.gms.oss-licenses-plugin")
}

android {
    namespace = "local.oss.chronicle"
    compileSdk = 36

    lint {
        abortOnError = false
        baseline = file("lint-baseline.xml")
        checkReleaseBuilds = true
        checkAllWarnings = true
    }

    defaultConfig {
        applicationId = "local.oss.chronicle"
        minSdk = 33
        targetSdk = 36
        versionCode = 39
        versionName = "0.60.15"

        testInstrumentationRunner = "local.oss.chronicle.application.ChronicleTestRunner"
    }

    signingConfigs {
        create("release") {
            // For GitHub Actions: use environment variables
            // For local builds: use keystore.properties file
            val keystorePropertiesFile = rootProject.file("keystore.properties")

            if (System.getenv("KEYSTORE_FILE") != null) {
                // GitHub Actions signing configuration
                storeFile = file(System.getenv("KEYSTORE_FILE"))
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            } else if (keystorePropertiesFile.exists()) {
                // Local signing configuration from keystore.properties
                val keystoreProperties = Properties()
                keystoreProperties.load(keystorePropertiesFile.inputStream())

                storeFile = file(keystoreProperties["storeFile"].toString())
                storePassword = keystoreProperties["storePassword"].toString()
                keyAlias = keystoreProperties["keyAlias"].toString()
                keyPassword = keystoreProperties["keyPassword"].toString()
            }
            // If neither exists, release builds will use debug signing
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )

            // Use release signing config if available
            val releaseSigningConfig = signingConfigs.findByName("release")
            if (releaseSigningConfig?.storeFile != null) {
                signingConfig = releaseSigningConfig
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"

        freeCompilerArgs +=
            listOf(
                "-opt-in=kotlinx.coroutines.ExperimentalCoroutinesApi",
                // Enable Kotlin 2.0+ support for KAPT
                "-Xallow-unstable-dependencies",
            )
    }
    buildFeatures {
        dataBinding = true
        buildConfig = true
    }
}

// Play Publisher configuration
play {
    // Service account from environment variable (CI) or file (local)
    val credentialsFile = file("play-store-credentials.json")
    if (System.getenv("PLAY_STORE_SERVICE_ACCOUNT_JSON") != null) {
        serviceAccountCredentials.set(
            file(System.getenv("PLAY_CREDENTIALS_FILE") ?: "play-credentials.json"),
        )
    } else if (credentialsFile.exists()) {
        serviceAccountCredentials.set(credentialsFile)
    }

    track.set("internal") // Default track
    defaultToAppBundles.set(true) // Use AAB by default

    // For apps not yet published to production, releases must be drafts
    // Change to COMPLETED once the app is live on Play Store
    releaseStatus.set(com.github.triplet.gradle.androidpublisher.ReleaseStatus.DRAFT)
}

// KSP configuration for Room
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
    arg("room.expandProjection", "true")
}

// KAPT still needed for data binding (doesn't fully support KSP yet)
kapt {
    correctErrorTypes = true
}

dependencies {

    implementation(libs.material)
    implementation(libs.glide)
    implementation(libs.timber)
    implementation(libs.iapwrapper)
    implementation(libs.fetch)
    implementation(libs.work)
    implementation(libs.result)
    implementation(libs.swiperefresh)
    implementation(libs.seismic)
    implementation(libs.browserx)
    implementation(libs.oss)
    implementation(libs.appcompat)
    implementation(libs.annotation)
    implementation(libs.coroutines)
    compileOnly(libs.facebook.infer.annotation)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter)

    implementation(libs.okhttp3)
    implementation(libs.okhttp3.logging)

    implementation(libs.moshi)
    // Removed moshi-codegen KAPT processor - deprecated for Kotlin 2.x
    // Moshi will use reflection-based adapters instead

    implementation(libs.fresco)
    implementation(libs.fresco.imagepipeline)

    implementation(libs.room.runtime)
    ksp(libs.room.compiler)
    implementation(libs.room.ktx)

    implementation(libs.dagger)
    ksp(libs.dagger.compiler)

    implementation(libs.media3.exoplayer)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)
    implementation(libs.media3.datasource)
    implementation(libs.media3.cast)

    /*
     * Local Tests
     */
    testImplementation(libs.dagger)
    kspTest(libs.dagger.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.hamcrest)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.androidx.arch.core.testing)

    /*
     * Instrumented Tests
     */
    androidTestImplementation(libs.dagger)
    kspAndroidTest(libs.dagger.compiler)

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.coroutines.test)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.espresso.contrib)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.ext.junit.ktx)
    androidTestImplementation(libs.screengrab)
}

tasks.matching { it.name.contains("DebugAndroidTest") && !it.name.contains("Lint") }.configureEach {
    enabled = false
}
