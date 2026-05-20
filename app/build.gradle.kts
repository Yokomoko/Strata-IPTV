import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

/**
 * Resolve the TMDB API key from (in order):
 *   1. Environment variable `TMDB_API_KEY` (used by GitHub Actions /
 *      release pipeline — set as a repository secret).
 *   2. `local.properties` entry `tmdb.api.key=...` (local dev only;
 *      file is gitignored by Android Studio defaults).
 *   3. Empty string fallback so the build never fails on missing key;
 *      enrichment just no-ops at runtime.
 *
 * The key is exposed via `BuildConfig.TMDB_API_KEY` rather than a
 * hard-coded constant so it never lands in git history.
 */
val tmdbApiKey: String = run {
    System.getenv("TMDB_API_KEY")?.takeIf { it.isNotBlank() }
        ?: Properties().also { props ->
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use(props::load)
        }.getProperty("tmdb.api.key").orEmpty()
}

android {
    namespace = "com.strata.tv"
    compileSdk = 34

    defaultConfig {
        // Application ID was originally com.strata.tv and we bumped
        // through com.strata.tvapp.  Fire OS's launcher banner cache
        // is so sticky that even a fresh package ID doesn't always
        // refresh.  tv.strata.app gives the cleanest possible reset.
        // Kotlin namespace stays com.strata.tv so source code is
        // untouched.
        applicationId = "tv.strata.app"
        // Min SDK 21 covers every Fire Stick currently in service.
        // Fire Stick 4K Max is API 28 (Fire OS 7) or API 30 (Fire OS 8).
        minSdk = 21
        targetSdk = 34
        versionCode = 36
        versionName = "0.3.20"

        // Custom JUnit runner that swaps the Application class for
        // [HiltTestApplication] so @AndroidEntryPoint / @HiltViewModel
        // bindings resolve in instrumented tests.
        testInstrumentationRunner = "com.strata.tv.HiltTestRunner"

        // TMDB API key — injected at build time, never in source.
        // See the `tmdbApiKey` resolver above.
        buildConfigField("String", "TMDB_API_KEY", "\"$tmdbApiKey\"")

        // Keeps the APK size down; we only ship for the architectures the
        // Fire Stick lineup actually uses.
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    // Stable release signing config.  Resolves keystore + passwords
    // from (in order):
    //   1. Environment variables (set by GitHub Actions from secrets).
    //   2. local.properties (developer machine — gitignored).
    //   3. Falls back to NOT configured, in which case the release
    //      buildType uses the auto-generated debug keystore — same as
    //      before this was set up.
    //
    // Keeping the same keystore across all release builds (local +
    // CI) means `adb install -r` works without forcing the user to
    // uninstall + lose their library every time a new APK ships.
    val releaseKeystore: File? = run {
        val f = rootProject.file("app/release.jks")
        if (f.exists()) f else null
    }
    val releaseKeystorePassword: String? =
        System.getenv("RELEASE_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: Properties().also { props ->
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.inputStream()?.use(props::load)
            }.getProperty("release.keystore.password")
    val releaseKeyAlias: String? =
        System.getenv("RELEASE_KEY_ALIAS")?.takeIf { it.isNotBlank() }
            ?: Properties().also { props ->
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.inputStream()?.use(props::load)
            }.getProperty("release.key.alias")
            ?: "strata"
    val releaseKeyPassword: String? =
        System.getenv("RELEASE_KEY_PASSWORD")?.takeIf { it.isNotBlank() }
            ?: Properties().also { props ->
                rootProject.file("local.properties").takeIf { it.exists() }
                    ?.inputStream()?.use(props::load)
            }.getProperty("release.key.password")
    val releaseSigningConfigured = releaseKeystore != null &&
        !releaseKeystorePassword.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
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
            // Prefer the stable release keystore.  If neither env vars
            // nor local.properties have been set up, fall through to
            // the debug keystore so a fresh checkout still builds.
            signingConfig = if (releaseSigningConfigured) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
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
        compose = true
        buildConfig = true
    }

    sourceSets["main"].kotlin.srcDir("src/main/kotlin")
    sourceSets["androidTest"].kotlin.srcDir("src/androidTest/kotlin")

    // Use 17 for unit-test runs too.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    // TV-specific Compose
    implementation(libs.tv.foundation)
    implementation(libs.tv.material)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)
    implementation(libs.hilt.work)
    ksp(libs.hilt.androidx.compiler)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Media3 — ExoPlayer + HLS support + Compose-friendly PlayerView
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.kotlinx.serialization.json)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Image loading
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // Background workers
    implementation(libs.work.runtime.ktx)

    // Preferences DataStore — persistent app settings (provider config, etc).
    implementation(libs.datastore.preferences)

    // Unit tests — pure JVM, no Android runtime needed.  Domain
    // package + parsers must stay JVM-testable so we can run TDD
    // on the algorithms in seconds rather than minutes.
    testImplementation(libs.junit)
    testImplementation(libs.truth)
    testImplementation(libs.kotlinx.coroutines.test)

    // Instrumented (e2e) tests — run on a connected Fire Stick /
    // emulator via `./gradlew :app:connectedDebugAndroidTest`.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.truth)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.mockwebserver)
    androidTestImplementation(libs.work.runtime.ktx)
}
