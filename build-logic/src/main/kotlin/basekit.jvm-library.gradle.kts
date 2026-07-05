// Convention for a plain JVM library published to Maven Central (e.g. the KSP processor).
// Publishing is property-driven via the vanniktech plugin (see basekit.kmp-library).
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
