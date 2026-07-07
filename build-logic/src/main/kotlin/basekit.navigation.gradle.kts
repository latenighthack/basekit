import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Consumer wiring for the navigation codegen. A KMP module applies this (alongside the Kotlin
// Multiplatform plugin) to turn on the basekit navigation processor. The navigator/route
// interfaces are pure common types, so they are generated only in the commonMain metadata pass
// (build/generated/ksp/metadata/commonMain) and wired back into commonMain.
//
// The consumer chooses the output package with:  ksp { arg("Basekit_NavigationPackage", "<pkg>") }
//
// To also generate the ViewModel test harness (TestViewModelRegistry + TestClientNavigator, emitted into
// <pkg>.test), opt in with an extra KSP arg and depend on the harness runtime from commonMain:
//
//   ksp { arg("Basekit_GenerateTestNavigator", "true") }
//   // commonMain: implementation(project(":basekit-navigation-test"))
//
// A consumer then implements the generated TestViewModelRegistry (one line per @Destination) and drives
// tests through TestClientNavigator(registry).awaitViewModel<T>(). Because the harness is generated into
// commonMain, keep those types out of published library APIs (they are test-only glue).
plugins {
    id("com.google.devtools.ksp")
}

// The processor runs only in the common metadata pass.
dependencies {
    add("kspCommonMainMetadata", project(":basekit-ksp"))
}

extensions.configure<KotlinMultiplatformExtension> {
    sourceSets.named("commonMain") {
        kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
    }
}

afterEvaluate {
    // Generation must run before anything compiles the generated commonMain sources.
    tasks.withType(KotlinCompilationTask::class.java).configureEach {
        if (name != "kspCommonMainKotlinMetadata") {
            dependsOn("kspCommonMainKotlinMetadata")
        }
    }
}
