plugins {
    id("basekit.jvm-library")
}

dependencies {
    testImplementation(kotlin("test-junit5"))
    implementation(libs.ksp.api)
}
