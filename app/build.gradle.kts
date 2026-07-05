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
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)
    // FFmpeg audio decoders - fallback for codecs device MediaCodec lacks.
    // Self-built from androidx/media@1.9.0 decoder_ffmpeg + FFmpeg 6.0 with
    // ONLY mp3/aac/vorbis/opus/flac enabled (~2.3MB all ABIs). Rebuild
    // procedure: see BUILD_FFMPEG.md. Transitive deps (media3-decoder) are
    // satisfied by media3-exoplayer above - file deps carry none themselves.
    // NOTE: stays a vendored aar for now; swaps to the lib-media3-ffmpeg-android
    // jitpack coordinate at the library recompose onto capullo-audio.
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))

    // Snapcast native binaries - commit 78d1c48 includes channel switching + metadata passthrough
    implementation(libs.lib.snapcast.android)

    // Ktor - WebSocket client for Snapcast JSON-RPC control
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.serialization.kotlinx.json)

    // Serialization - Snapcast JSON-RPC types
    implementation(libs.kotlinx.serialization.json)

    // Network
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    // Room (favorites)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Hilt (DI) - @HiltAndroidApp / @HiltViewModel; a second KSP processor alongside Room
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

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

    // NewPipe Extractor - YouTube search without API key
    implementation(libs.newpipe.extractor)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
