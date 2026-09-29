import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Optional: put MAPBOX_TOKEN=pk.xxx in local.properties (git-ignored).
// If absent the app falls back to the public OSRM demo server at runtime.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
val mapboxToken: String = localProps.getProperty("MAPBOX_TOKEN", "")

android {
    namespace = "com.veldash"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.veldash"
        // 21 = Android 5.0. Covers the oldest head units that can still run MapLibre 11 (OpenGL ES 2.0).
        minSdk = 21
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        // Ship only English resources: strips every locale folder pulled in by dependencies.
        resourceConfigurations += listOf("en")

        buildConfigField("String", "MAPBOX_TOKEN", "\"$mapboxToken\"")
        // Fallback when MAPBOX_TOKEN is empty. Replace with your own OSRM instance for production use.
        buildConfigField("String", "OSRM_BASE_URL", "\"https://router.project-osrm.org\"")
    }

    // Head-unit SoCs are ARM only: x86/x86_64 are excluded entirely (halves the native-lib payload).
    // One APK per ABI: a 32-bit head unit never downloads the arm64 blob (and vice versa).
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = false
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
        }
        debug {
            // Keep debug builds honest: same ABI filtering, no extra instrumentation.
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        // viewBinding is compile-time codegen only -> zero runtime cost, and it removes findViewById boilerplate.
        viewBinding = true
        buildConfig = true
        // Everything else off. Each one adds build time and, in some cases, runtime classes.
        aidl = false
        renderScript = false
        shaders = false
        resValues = false
        dataBinding = false
        compose = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // No core-library desugaring: it drags in a ~200 KB runtime. We stay on APIs available at minSdk 21.
        isCoreLibraryDesugaringEnabled = false
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            // Strip Kotlin intrinsics null-check calls from the DEX. Fewer instructions on a hot path.
            "-Xno-call-assertions",
            "-Xno-param-assertions",
            "-Xno-receiver-assertions",
        )
    }

    packaging {
        // Uncompressed native libs are mmapped straight from the APK: less RAM, faster cold start.
        jniLibs.useLegacyPackaging = false
        resources {
            // Junk that dependencies ship and we never read.
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/*.kotlin_module",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/DEPENDENCIES",
                "META-INF/**/LICENSE*",
                "kotlin/**",
                "DebugProbesKt.bin",
                // OkHttp's 41 KB public-suffix list: only consulted by cookie handling, which we never use.
                "okhttp3/internal/publicsuffix/*",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // Map renderer. Verified transitive deps (11.8.8): OkHttp/Okio, androidx.core/fragment/lifecycle,
    // coroutines 1.6.4, gson (GeoJSON models), timber. NO appcompat -> we use plain android.app.Activity.
    // R8 full mode strips the parts of these we never touch.
    implementation(libs.maplibre.android.sdk)

    // Directions API client. Raw OkHttp + org.json (in the Android platform). No Retrofit, no Gson, no Moshi.
    implementation(libs.okhttp)

    implementation(libs.androidx.annotation)

    // --- Offline routing (Step 4) ---
    // BRouter has no Maven artifact. It will be added as a pure-Java source module
    // (:brouter-core) in the offline-routing step. GraphHopper is deliberately NOT used:
    // its graph loader alone exceeds our 60 MB RAM budget on a 1 GB head unit.
    // implementation(project(":brouter-core"))
}
