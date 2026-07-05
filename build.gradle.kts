plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.skie) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published modules derive their coordinates from GROUP / VERSION_NAME in gradle.properties
// (consumed by the maven-publish plugin). The values below keep the non-published demo module
// on the same group + version.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()
}
