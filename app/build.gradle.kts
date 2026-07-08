import java.text.SimpleDateFormat
import java.util.Date

plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin (see RadioCapullo).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val buildTime: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

android {
    namespace = "tech.capullo.quantumcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.quantumcast"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
    }

    flavorDimensions += "variant"
    productFlavors {
        create("prod") {
            dimension = "variant"
            applicationId = "tech.capullo.quantumcast"
            resValue("string", "app_name", "Quantumcast")
        }
        create("snap") {
            dimension = "variant"
            applicationId = "tech.capullo.quantumcast.clone"
            resValue("string", "app_name", "QCClone")
            versionNameSuffix = "-clone"
        }
    }

    // (Removed the legacy applicationVariants.all{} APK-rename block: it used AGP's internal
    // BaseVariantOutputImpl API - gone in AGP 9 - and only reproduced the default output name
    // `app-<flavor>-<buildtype>.apk`, which AGP already emits for a flavored build.)

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // New DSL for Kotlin 2.3 / AGP 9.x (mirrors RadioCapullo). compilerOptions, NOT jvmToolchain(17):
    // the Windows host JBR is 21 with no standalone JDK 17 to provision.
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
            // Kotlin 2.3 annotation-target opt-in (bears on serialization + Room annotations).
            freeCompilerArgs.add("-Xannotation-default-target=param-property")
        }
    }

    buildFeatures {
        compose = true
        // AGP 9 disables resValues by default; the prod/snap flavors set resValue("string","app_name",…).
        resValues = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    // Navigation3 (replaces the previously-unused navigation-compose 2.7.7)
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)

    // Media session (lock screen controls, notification) - replaces Media3 MediaSessionService
    implementation(libs.androidx.media)

    // Media3/ExoPlayer - decodes radio streams; a TeeAudioProcessor in a custom
    // DefaultAudioSink chain writes 44100:16:2 PCM into the Snapcast FIFO
    // (replaces libvlc-all + its sout transcode hack; see VLC_ALTERNATIVES.md)
    // Media3/ExoPlayer - QC's PlaybackService still owns the ExoPlayer→FIFO glue (Strategy 1), so
    // media3 stays a DIRECT compile dep. The native .so decoders it needs are pulled transitively:
    // lib-snapcast-android (12 snapcast .so) and lib-media3-ffmpeg-android (libffmpegJNI.so, loaded
    // reflectively by FifoRenderersFactory) both come via capullo-audio's runtime deps - QC no longer
    // declares them directly (a later cleanup). The FFmpeg LGPL NOTICE / BUILD_FFMPEG.md stay in the
    // repo since QC's APK still redistributes libffmpegJNI.so.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)

    // capullo-source-radiobrowser (Layer 3) - the radio ingress: Radio Browser API, favorites Room DB,
    // Station/Country/TrackLookup models, PlaylistResolver, Shazam identification. The library recompose replaced QC's
    // local data/{api,model,db,repository} + shazam/* + PlaylistResolver copies with this library (single
    // source of truth). Its Retrofit/Gson/OkHttp/Room/NewPipe deps are internal to the lib (implementation
    // scope) and present at runtime transitively; QC's own now-redundant direct declarations of those are
    // left in place for this isolated commit and pruned in a later cleanup.
    implementation(libs.capullo.source.radiobrowser)

    // capullo-audio (Layer 2) - the delivery engine's public transport classes: SnapserverProcess,
    // SnapclientProcess, SnapcastControlClient + JSON-RPC types, SnapcontrolPlugin (StateFlow<NowPlaying>
    // + PlaybackController), Nsd/Discovery, and the ExoPlayer→FIFO sink. The library recompose replaced QC's local
    // snapcast/* + player/FifoAudioSink copies with this library. QC keeps its own PlaybackService
    // orchestration and drives the plugin via an in-service NowPlaying adapter (Strategy 1 - the
    // CapulloAudioEngine facade is intentionally not used). Also the sole (transitive) provider of the
    // snapcast + ffmpeg native .so and of ktor (its SnapcastControlClient websocket) - QC references
    // none of those at compile time anymore, so their former direct declarations were dropped here.
    implementation(libs.capullo.audio)
    implementation(libs.capullo.audio.ui) // shared control sheet + QR dialog

    // Serialization runtime - Navigation3 `@Serializable data object … : NavKey` route keys.
    implementation(libs.kotlinx.serialization.json)

    // Hilt (DI) - @HiltAndroidApp / @HiltViewModel; drives KSP.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Images
    implementation(libs.coil.compose)

    implementation(libs.androidx.appcompat)
    implementation(libs.android.material)

    // Settings persistence
    implementation(libs.androidx.datastore.preferences)

    // Drag-to-reorder for LazyColumn
    implementation(libs.reorderable)

    // QR code generation (share listening address in Qcast tab)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
