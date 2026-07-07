import org.gradle.accessors.dm.LibrariesForLibs

// Base convention for a Kotlin Multiplatform library published to Maven Central.
// Publishing is property-driven: the vanniktech plugin reads GROUP / VERSION_NAME / SONATYPE_HOST /
// RELEASE_SIGNING_ENABLED / POM_* from gradle.properties, and per-module POM_ARTIFACT_ID / POM_NAME /
// POM_DESCRIPTION from each module's own gradle.properties — so no mavenPublishing{} block is needed here.
plugins {
    id("org.jetbrains.kotlin.multiplatform")
    id("com.android.library")
    id("com.vanniktech.maven.publish")
}

val libs = the<LibrariesForLibs>()

kotlin {
    jvmToolchain(17)

    jvm()
    androidTarget { publishLibraryVariants("release") }
    iosArm64()
    iosX64()
    iosSimulatorArm64()
    js(IR) {
        browser()
        nodejs()
        binaries.library()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(libs.kotlin.stdlib)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
        val commonTest by getting {
            dependencies {
                implementation(libs.kotlin.test)
            }
        }
    }
}

android {
    // e.g. project "basekit-navigation" -> com.latenighthack.basekit.navigation
    namespace = "com.latenighthack.basekit." + project.name.removePrefix("basekit-").replace('-', '.')
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
}

// JS is a compile-only test target: tests execute on the JVM (per-module commonTest), and the JS test
// sources still compile (compileTestKotlinJs), proving they are valid JS code. Every JS module shares one
// build/js package dir, so a module's JS test task reads sibling modules' compiled output and trips
// Gradle's implicit-dependency validation; disabling JS test *execution* avoids that without losing
// compilation coverage. Keep this until the KGP wires those cross-module task dependencies itself.
tasks.withType(org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest::class.java).configureEach {
    enabled = false
}
