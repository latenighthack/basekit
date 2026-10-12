plugins {
    id("basekit.jvm-library")
}

// Reads specs through the extractors of the two existing processors. Their jars carry their own
// processor registrations, so the catalog plugin applies the navigation and viewmodel plugins too:
// the same processor artifacts then appear once on the KSP classpath, and nothing runs twice.
dependencies {
    implementation(libs.ksp.api)
    implementation(project(":basekit-ksp"))
    implementation(project(":basekit-viewmodel-ksp"))
    testImplementation(kotlin("test-junit5"))
    testImplementation(libs.snakeyaml)
}
