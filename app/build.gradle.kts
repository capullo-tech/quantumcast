import java.text.SimpleDateFormat
import java.util.Date

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

val buildTime: String = SimpleDateFormat("yyyyMMdd_HHmmss").format(Date())

android {
    namespace = "com.quantumcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.quantumcast"
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

    applicationVariants.all {
        outputs.all {
            (this as com.android.build.gradle.internal.api.BaseVariantOutputImpl).outputFileName =
                "app-${flavorName}-${buildType.name}.apk"
        }
    }

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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
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
    val composeBom = platform("androidx.compose:compose-bom:2024.11.00")
    implementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.0")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.navigation:navigation-compose:2.7.7")

    // Media session (lock screen controls, notification) - replaces Media3 MediaSessionService
    implementation("androidx.media:media:1.7.0")

    // Media3/ExoPlayer - decodes radio streams; a TeeAudioProcessor in a custom
    // DefaultAudioSink chain writes 44100:16:2 PCM into the Snapcast FIFO
    // (replaces libvlc-all + its sout transcode hack; see VLC_ALTERNATIVES.md)
    implementation("androidx.media3:media3-exoplayer:1.9.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.9.0")
    // FFmpeg audio decoders - fallback for codecs device MediaCodec lacks.
    // Self-built from androidx/media@1.9.0 decoder_ffmpeg + FFmpeg 6.0 with
    // ONLY mp3/aac/vorbis/opus/flac enabled (~2.3MB all ABIs). Rebuild
    // procedure: see BUILD_FFMPEG.md. Transitive deps (media3-decoder) are
    // satisfied by media3-exoplayer above - file deps carry none themselves.
    implementation(files("libs/lib-decoder-ffmpeg-release.aar"))

    // Snapcast native binaries - commit 78d1c48 includes channel switching + metadata passthrough
    implementation("com.github.capullo-tech.lib-snapcast-android:lib-snapcast-android:78d1c48")

    // Ktor - WebSocket client for Snapcast JSON-RPC control
    implementation("io.ktor:ktor-client-okhttp:2.3.12")
    implementation("io.ktor:ktor-client-websockets:2.3.12")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.12")

    // Serialization - Snapcast JSON-RPC types
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    // Network
    implementation("com.squareup.retrofit2:retrofit:2.11.0")
    implementation("com.squareup.retrofit2:converter-gson:2.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // Room (favorites)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Images
    implementation("io.coil-kt:coil-compose:2.6.0")

    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // Settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Drag-to-reorder for LazyColumn
    implementation("sh.calvin.reorderable:reorderable:2.4.3")

    // QR code generation (share listening address in Qcast tab)
    implementation("com.google.zxing:core:3.5.3")

    // NewPipe Extractor - YouTube search without API key
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.24.2")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
