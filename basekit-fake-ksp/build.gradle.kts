plugins {
    id("basekit.jvm-library")
}

// Reads specs through the extractors of the navigation and viewmodel processors, as basekit-catalog-ksp
// does. The fakes plugin applies the navigation and viewmodel plugins too, so each processor jar appears
// once on the KSP classpath.
dependencies {
    implementation(libs.ksp.api)
    implementation(project(":basekit-ksp"))
    implementation(project(":basekit-viewmodel-ksp"))
    testImplementation(kotlin("test-junit5"))
}
