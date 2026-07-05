import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Consumer wiring for the navigation codegen. A KMP module applies this (alongside the Kotlin
// Multiplatform plugin) to turn on the basekit navigation processor. The navigator/route
// interfaces are pure common types, so they are generated only in the commonMain metadata pass
// (build/generated/ksp/metadata/commonMain) and wired back into commonMain.
//
// The consumer chooses the output package with:  ksp { arg("Basekit_NavigationPackage", "<pkg>") }
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
