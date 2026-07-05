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
