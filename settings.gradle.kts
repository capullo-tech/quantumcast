pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
    versionCatalogs {
        // Shared org toolchain, pinned by commit from jitpack.
        create("libs") { from("com.github.capullo-tech:build-conventions:22483910a0cd6d7e583ec3d268ad1c8f872bb4ba") }
        // Local pins: inter-repo capullo coordinates (versioned per release) + QC's own deps.
        create("pins") { from(files("gradle/pins.versions.toml")) }
    }
}
rootProject.name = "QuantumCast"
include(":app")

// Dev/release toggle for the capullo-tech first-party libraries (library recompose).
// When a library is checked out as a sibling (local co-development / the Windows-host share build,
// where all capullo-tech repos live side-by-side), build it from source via a composite build and
// substitute it for its jitpack coordinate. On CI (single repo, no siblings) the block is skipped and
// the coordinate resolves from jitpack.io - same pattern the library repos use for capullo-audio-contracts.
if (file("../capullo-source-radiobrowser").exists()) {
    includeBuild("../capullo-source-radiobrowser") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech:capullo-source-radiobrowser"))
                .using(project(":capullo-source-radiobrowser"))
        }
    }
}
if (file("../capullo-audio").exists()) {
    includeBuild("../capullo-audio") {
        dependencySubstitution {
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio"))
                .using(project(":capullo-audio"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-audio-ui"))
                .using(project(":capullo-audio-ui"))
            substitute(module("com.github.capullo-tech.capullo-audio:capullo-tunnel"))
                .using(project(":capullo-tunnel"))
        }
    }
}
