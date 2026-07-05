plugins {
    alias(libs.plugins.android.application) apply false
    // No kotlin.android alias: AGP 9.0+ ships built-in Kotlin (see RadioCapullo / the library repos).
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
