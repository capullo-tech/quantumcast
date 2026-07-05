plugins {
    alias(libs.plugins.android.application) apply false
    // No kotlin.android alias: AGP 9.0+ ships built-in Kotlin (see RadioCapullo / the library repos).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.spotless)
}

// Org code-style standard (mirrors RadioCapullo). `./gradlew spotlessApply` to format,
// `spotlessCheck` to verify (wired into the Build CI workflow).
spotless {
    kotlin {
        target("**/*.kt")
        ktlint(libs.versions.ktlint.get())
            .editorConfigOverride(
                mapOf(
                    "ktlint_function_naming_ignore_when_annotated_with" to "Composable",
                    "ktlint_standard_annotation" to "disabled",
                    "max_line_length" to 100,
                    // QC-specific relaxations vs the RadioCapullo baseline (which passes these only
                    // because its code predates neither issue). QC's pre-existing code uses Compose
                    // wildcard imports across ~16 files (ktlint can't auto-expand them; expansion
                    // needs an interactive IDE, unavailable in the headless build) and a wider
                    // line budget. Hand-fixing that on UI that the recompose rewrites isn't worth it.
                    // Revisit / tighten in build-conventions. All other rules stay on.
                    "ktlint_standard_no-wildcard-imports" to "disabled",
                    "ktlint_standard_max-line-length" to "disabled",
                ),
            )
    }
}
