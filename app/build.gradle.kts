import com.android.build.api.artifact.SingleArtifact

plugins {
    alias(libs.plugins.android.application)
    // No kotlin.android: AGP 9.0+ ships built-in Kotlin (see RadioCapullo).
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tech.capullo.quantumcast"
    compileSdk = 36

    defaultConfig {
        applicationId = "tech.capullo.quantumcast"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "1.0"
    }

    // Release signing. Keystore + passwords come from env vars (CI secrets wired in Build.yml;
    // exported vars for a local release build on the Windows host). If the keystore isn't present (e.g. a
    // fork PR without secrets) the release build is left unsigned rather than failing, so CI still
    // validates the build.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_FILE")
        ?.let(::file)
        ?.takeIf { it.exists() && it.length() > 0L }
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            // Minify OFF for the 1.0.0 cut: keeps the release APK code-identical to the debug build
            // already device-verified. R8/shrinking is a separate, later hardening task (the
            // reflectively-loaded FFmpeg decoder needs keep-rules + a device check first).
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
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

// Self-identifying APK copies: quantumcast-v<versionName>-vc<versionCode>-<variant>.apk under
// build/outputs/apk-named/<variant>/, produced automatically after each assemble. Uses only the
// public artifacts API (SingleArtifact.APK) - no internal AGP classes - so it survives AGP upgrades.
// The standard app-<variant>.apk stays in place for installDebug and friends.
androidComponents {
    onVariants { variant ->
        val vn = android.defaultConfig.versionName
        val vc = android.defaultConfig.versionCode
        val cap = variant.name.replaceFirstChar { it.uppercase() }
        val copyNamedApk = tasks.register<Copy>("copyNamedApk$cap") {
            from(variant.artifacts.get(SingleArtifact.APK)) {
                include("*.apk")
                rename { "quantumcast-v$vn-vc$vc-${variant.name}.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk-named/${variant.name}"))
        }
        afterEvaluate { tasks.named("assemble$cap").configure { finalizedBy(copyNamedApk) } }
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
    implementation(pins.androidx.media)

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

    // capullo-source-radiobrowser - the radio source: Radio Browser API, favorites Room DB,
    // Station/Country/TrackLookup models, PlaylistResolver, Shazam identification. The library recompose replaced QC's
    // local data/{api,model,db,repository} + shazam/* + PlaylistResolver copies with this library (single
    // source of truth). Its Retrofit/Gson/OkHttp/Room/NewPipe deps are internal to the lib (implementation
    // scope) and present at runtime transitively; QC's own now-redundant direct declarations of those are
    // left in place for this isolated commit and pruned in a later cleanup.
    implementation(pins.capullo.source.radiobrowser)

    // capullo-audio - the delivery engine's public transport classes: SnapserverProcess,
    // SnapclientProcess, SnapcastControlClient + JSON-RPC types, SnapcontrolPlugin (StateFlow<NowPlaying>
    // + PlaybackController), Nsd/Discovery, and the ExoPlayer→FIFO sink. The library recompose replaced QC's local
    // snapcast/* + player/FifoAudioSink copies with this library. QC keeps its own PlaybackService
    // orchestration and drives the plugin via an in-service NowPlaying adapter (Strategy 1 - the
    // CapulloAudioEngine facade is intentionally not used). Also the sole (transitive) provider of the
    // snapcast + ffmpeg native .so and of ktor (its SnapcastControlClient websocket) - QC references
    // none of those at compile time anymore, so their former direct declarations were dropped here.
    implementation(pins.capullo.audio)
    implementation(pins.capullo.audio.ui) // shared control sheet + QR dialog

    // Serialization runtime - Navigation3 `@Serializable data object … : NavKey` route keys.
    implementation(libs.kotlinx.serialization.json)

    // Hilt (DI) - @HiltAndroidApp / @HiltViewModel; drives KSP.
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)

    // Images
    implementation(pins.coil.compose)

    implementation(pins.androidx.appcompat)
    implementation(pins.android.material)

    // Settings persistence
    implementation(pins.androidx.datastore.preferences)

    // Drag-to-reorder for LazyColumn
    implementation(pins.reorderable)

    // QR code generation (share listening address in Qcast tab)
    implementation(libs.zxing.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
